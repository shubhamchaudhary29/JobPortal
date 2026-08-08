package com.example.backend.job.infrastructure;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.TextCriteria;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Repository
public class JobSearchRepository {
    private final MongoTemplate mongo;

    public JobSearchRepository(MongoTemplate mongo) { this.mongo = mongo; }

    public Page<JobDocument> search(String q, String location, String source, Pageable pageable) {
        List<Criteria> filters = new ArrayList<>();
        if (location != null && !location.isBlank())
            filters.add(Criteria.where("location").regex(Pattern.compile("^" + Pattern.quote(location.trim()) + "$", Pattern.CASE_INSENSITIVE)));
        if (source != null && !source.isBlank()) filters.add(Criteria.where("source").is(source.trim().toLowerCase()));
        Query query = new Query();
        if (q != null && !q.isBlank()) query.addCriteria(TextCriteria.forDefaultLanguage().matchingPhrase(q.trim()));
        if (!filters.isEmpty()) query.addCriteria(new Criteria().andOperator(filters));
        long total = mongo.count(Query.of(query).limit(-1).skip(-1), JobDocument.class);
        query.with(pageable);
        return new PageImpl<>(mongo.find(query, JobDocument.class), pageable, total);
    }
}
