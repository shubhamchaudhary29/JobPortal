package com.example.backend.integration.aggregation;

import com.example.backend.integration.jobs.JobFetchRequest;
import com.example.backend.integration.jobs.JobSource;
import com.example.backend.integration.reliability.ProviderFailureException;
import com.example.backend.shared.error.BadRequestException;
import com.example.backend.shared.error.ConflictException;
import com.example.backend.shared.error.ResourceNotFoundException;
import com.example.backend.shared.pagination.PageResponse;
import com.example.backend.shared.provider.ProviderCatalog;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * ADMIN management of {@code employer_registry}. Every change records the actor and time on the entry and an
 * append-only {@code employer_registry_audit} record. A board's provider and ID are its identity and cannot be
 * edited. Validation fetches the board once through its provider's {@link JobSource} and never stores jobs.
 */
@Service
public class EmployerRegistryAdminService {
    private static final Pattern BOARD = Pattern.compile("[A-Za-z0-9_-]{1,100}");
    private final MongoTemplate mongo;
    private final List<JobSource> sources;
    private final Clock clock;

    public EmployerRegistryAdminService(MongoTemplate mongo, List<JobSource> sources, Clock clock) {
        this.mongo = mongo;
        this.sources = sources;
        this.clock = clock;
    }

    public PageResponse<EntryView> list(String provider, Boolean enabled, String text, int page, int size) {
        if (page < 0) throw new BadRequestException("Page must not be negative");
        if (size < 1 || size > 100) throw new BadRequestException("Size must be between 1 and 100");
        List<Criteria> filters = new ArrayList<>();
        if (provider != null && !provider.isBlank()) filters.add(Criteria.where("source").is(source(provider)));
        if (enabled != null) filters.add(Criteria.where("enabled").is(enabled));
        if (text != null && !text.isBlank()) {
            String trimmed = text.trim();
            if (trimmed.length() > 100) throw new BadRequestException("Search text is too long");
            Pattern pattern = Pattern.compile(Pattern.quote(trimmed), Pattern.CASE_INSENSITIVE);
            filters.add(new Criteria().orOperator(Criteria.where("company").regex(pattern),
                    Criteria.where("boardId").regex(pattern)));
        }
        Criteria criteria = filters.isEmpty() ? new Criteria() : new Criteria().andOperator(filters);
        Sort sort = Sort.by(Sort.Order.asc("source"), Sort.Order.asc("boardKey"), Sort.Order.asc("id"));
        PageRequest pageable = PageRequest.of(page, size, sort);
        List<EntryView> content = mongo.find(Query.query(criteria).with(pageable), EmployerRegistryDocument.class)
                .stream().map(EntryView::from).toList();
        long total = mongo.count(Query.query(criteria), EmployerRegistryDocument.class);
        return PageResponse.from(new PageImpl<>(content, pageable, total));
    }

    public EntryView create(String provider, String boardId, String company, Boolean enabled, String actor) {
        EmployerRegistryProperties.Source source = source(provider);
        String board = board(boardId);
        String name = company(company);
        Instant now = clock.instant();
        EmployerRegistryDocument entry = new EmployerRegistryDocument();
        entry.setSource(source);
        entry.setBoardId(board);
        entry.setBoardKey(EmployerRegistryDocument.boardKey(board));
        entry.setCompany(name);
        entry.setCompanyKey(EmployerRegistryDocument.companyKey(name));
        entry.setEnabled(enabled == null || enabled);
        entry.setOrigin(EmployerRegistryDocument.Origin.ADMIN);
        entry.setCreatedAt(now);
        entry.setCreatedBy(actor(actor));
        entry.setUpdatedAt(now);
        entry.setUpdatedBy(actor(actor));
        try {
            mongo.insert(entry);
        } catch (DuplicateKeyException duplicate) {
            throw new ConflictException("This board already exists or the company already has an enabled board");
        }
        audit(entry.getId(), EmployerRegistryAuditDocument.Action.CREATE, actor, null, snapshot(entry));
        return EntryView.from(entry);
    }

    public EntryView update(String id, String company, Boolean enabled, String disabledReason, String actor) {
        EmployerRegistryDocument entry = find(id);
        Map<String, Object> before = snapshot(entry);
        if (company != null) {
            String name = company(company);
            entry.setCompany(name);
            entry.setCompanyKey(EmployerRegistryDocument.companyKey(name));
        }
        if (enabled != null) entry.setEnabled(enabled);
        if (disabledReason != null && disabledReason.trim().length() > 300) {
            throw new BadRequestException("Disabled reason is too long");
        }
        entry.setDisabledReason(entry.isEnabled() ? null
                : disabledReason != null && !disabledReason.isBlank() ? disabledReason.trim() : entry.getDisabledReason());
        Map<String, Object> after = snapshot(entry);
        if (before.equals(after)) return EntryView.from(entry);
        entry.setUpdatedAt(clock.instant());
        entry.setUpdatedBy(actor(actor));
        try {
            mongo.save(entry);
        } catch (DuplicateKeyException duplicate) {
            throw new ConflictException("The company already has an enabled board");
        }
        EmployerRegistryAuditDocument.Action action = !Objects.equals(before.get("enabled"), after.get("enabled"))
                ? (entry.isEnabled() ? EmployerRegistryAuditDocument.Action.ENABLE : EmployerRegistryAuditDocument.Action.DISABLE)
                : EmployerRegistryAuditDocument.Action.UPDATE;
        audit(entry.getId(), action, actor, before, after);
        return EntryView.from(entry);
    }

    public ValidationView validate(String id, String actor) {
        EmployerRegistryDocument entry = find(id);
        String provider = entry.getSource().provider().name();
        JobSource source = sources.stream().filter(candidate -> provider.equals(candidate.sourceName())).findFirst()
                .orElseThrow(() -> new BadRequestException("No job source is registered for " + provider));
        Classification classification;
        int count = 0;
        try {
            JobSource.FetchResult result = source.fetchWithMetadata(
                    new JobFetchRequest(null, 1, entry.getBoardId(), entry.getCompany()), () -> true);
            count = result == null ? 0 : result.jobs().size();
            classification = count == 0 ? Classification.EMPTY : Classification.ACTIVE;
        } catch (ProviderFailureException failure) {
            classification = switch (failure.kind()) {
                case CLIENT_ERROR -> Classification.INVALID;
                case MALFORMED_RESPONSE, PAYLOAD_LIMIT -> Classification.MALFORMED;
                default -> Classification.UNREACHABLE;
            };
        } catch (RuntimeException unexpected) {
            classification = Classification.UNREACHABLE;
        }
        ValidationView view = new ValidationView(entry.getId(), provider, entry.getBoardId(), classification, count,
                clock.instant());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("classification", classification.name());
        result.put("count", count);
        audit(entry.getId(), EmployerRegistryAuditDocument.Action.VALIDATE, actor, null, result);
        return view;
    }

    public PageResponse<AuditView> audit(String id, int page, int size) {
        if (page < 0) throw new BadRequestException("Page must not be negative");
        if (size < 1 || size > 100) throw new BadRequestException("Size must be between 1 and 100");
        find(id);
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("at"), Sort.Order.desc("id")));
        Query query = Query.query(Criteria.where("entryId").is(id));
        List<AuditView> content = mongo.find(Query.of(query).with(pageable), EmployerRegistryAuditDocument.class)
                .stream().map(AuditView::from).toList();
        return PageResponse.from(new PageImpl<>(content, pageable, mongo.count(query, EmployerRegistryAuditDocument.class)));
    }

    private EmployerRegistryDocument find(String id) {
        if (id == null || id.isBlank() || id.length() > 64) throw new BadRequestException("Invalid registry entry ID");
        EmployerRegistryDocument entry = mongo.findById(id, EmployerRegistryDocument.class);
        if (entry == null) throw new ResourceNotFoundException("Registry entry not found");
        return entry;
    }

    private void audit(String entryId, EmployerRegistryAuditDocument.Action action, String actor,
                       Map<String, Object> before, Map<String, Object> after) {
        EmployerRegistryAuditDocument record = new EmployerRegistryAuditDocument();
        record.setEntryId(entryId);
        record.setAction(action);
        record.setActor(actor(actor));
        record.setAt(clock.instant());
        record.setBefore(before);
        record.setAfter(after);
        mongo.insert(record);
    }

    private static Map<String, Object> snapshot(EmployerRegistryDocument entry) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("provider", entry.getSource().provider().name());
        values.put("boardId", entry.getBoardId());
        values.put("company", entry.getCompany());
        values.put("enabled", entry.isEnabled());
        values.put("disabledReason", entry.getDisabledReason());
        return values;
    }

    private static EmployerRegistryProperties.Source source(String provider) {
        return ProviderCatalog.find(provider == null ? null : provider.trim())
                .filter(ProviderCatalog.Provider::employerScoped)
                .map(found -> EmployerRegistryProperties.Source.valueOf(found.registrySource()))
                .orElseThrow(() -> new BadRequestException("Unsupported registry provider"));
    }

    private static String board(String boardId) {
        String trimmed = boardId == null ? "" : boardId.trim();
        if (!BOARD.matcher(trimmed).matches()) throw new BadRequestException("Invalid board ID");
        return trimmed;
    }

    private static String company(String company) {
        String trimmed = company == null ? "" : company.trim();
        if (trimmed.isEmpty() || trimmed.length() > 200) throw new BadRequestException("Company must be 1 to 200 characters");
        return trimmed;
    }

    private static String actor(String actor) {
        if (actor == null || actor.isBlank()) return "unknown";
        return actor.length() <= 200 ? actor : actor.substring(0, 200);
    }

    public enum Classification { ACTIVE, EMPTY, MALFORMED, INVALID, UNREACHABLE }

    public record EntryView(String id, String provider, String boardId, String company, boolean enabled,
                            String disabledReason, EmployerRegistryDocument.Origin origin, Instant createdAt,
                            String createdBy, Instant updatedAt, String updatedBy) {
        static EntryView from(EmployerRegistryDocument entry) {
            return new EntryView(entry.getId(), entry.getSource().provider().name(), entry.getBoardId(),
                    entry.getCompany(), entry.isEnabled(), entry.getDisabledReason(), entry.getOrigin(),
                    entry.getCreatedAt(), entry.getCreatedBy(), entry.getUpdatedAt(), entry.getUpdatedBy());
        }
    }

    public record ValidationView(String id, String provider, String boardId, Classification classification,
                                 int count, Instant checkedAt) { }

    public record AuditView(String id, EmployerRegistryAuditDocument.Action action, String actor, Instant at,
                            Map<String, Object> before, Map<String, Object> after) {
        static AuditView from(EmployerRegistryAuditDocument record) {
            return new AuditView(record.getId(), record.getAction(), record.getActor(), record.getAt(),
                    record.getBefore(), record.getAfter());
        }
    }
}
