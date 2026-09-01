package com.example.backend.copilot.infrastructure;

import com.example.backend.copilot.domain.CopilotModels.PersonalApplicationStage;
import com.example.backend.shared.error.BadRequestException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.regex.Pattern;

@Repository
public class WorkspaceQueryRepository {
    private final MongoTemplate mongo;
    public WorkspaceQueryRepository(MongoTemplate mongo) { this.mongo = mongo; }

    public Result find(String userId, PersonalApplicationStage stage, String search, int page, int size) {
        if (page < 0) throw new BadRequestException("page must be at least 0");
        if (size < 1 || size > 100) throw new BadRequestException("size must be between 1 and 100");
        if (search != null && search.length() > 100) throw new BadRequestException("search is too long");
        Criteria criteria = Criteria.where("userId").is(userId);
        if (stage != null) criteria = criteria.and("stage").is(stage);
        if (search != null && !search.isBlank()) {
            Pattern pattern = Pattern.compile(Pattern.quote(search.strip()), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            criteria = criteria.andOperator(new Criteria().orOperator(
                    Criteria.where("jobSnapshot.title").regex(pattern), Criteria.where("jobSnapshot.company").regex(pattern)));
        }
        Query countQuery = Query.query(criteria);
        long total = mongo.count(countQuery, CandidateJobWorkspaceDocument.class);
        Query dataQuery = Query.query(criteria).with(Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("_id")))
                .skip((long) page * size).limit(size);
        return new Result(mongo.find(dataQuery, CandidateJobWorkspaceDocument.class), total);
    }

    public record Result(List<CandidateJobWorkspaceDocument> content, long total) { }
}
