package com.example.backend.messaging.infrastructure;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
class MessageBulkRepositoryImpl implements MessageBulkRepository {
    private final MongoTemplate mongo;
    MessageBulkRepositoryImpl(MongoTemplate mongo) { this.mongo = mongo; }
    @Override public long markUnreadFromOtherSenderRead(String chatRoomId, String recipientId) {
        return mongo.updateMulti(Query.query(Criteria.where("chatRoomId").is(chatRoomId).and("read").is(false).and("senderId").ne(recipientId)), new Update().set("read", true), MessageDocument.class).getModifiedCount();
    }
}
