package com.example.backend.service;

import com.example.backend.entity.*;
import com.example.backend.exception.BadRequestException;
import com.example.backend.exception.ForbiddenException;
import com.example.backend.exception.ResourceNotFoundException;
import com.example.backend.repository.*;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class ChatService {

    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Autowired
    private ApplicationRepository applicationRepository;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    // ── 1. Create chat room (idempotent) ────────────────────────────────────

    /**
     * Called internally when an application status is set to ACCEPTED.
     * Idempotent — calling it multiple times for the same applicationId is safe.
     *
     * IMPORTANT: Application.userId stores the candidate's EMAIL (not MongoDB _id).
     * We therefore fetch the candidate with findByEmail(), not findById().
     */
    public ChatRoom createChatRoom(String applicationId) {
        // Idempotency check
        if (chatRoomRepository.existsByApplicationId(applicationId)) {
            return chatRoomRepository.findByApplicationId(applicationId).orElseThrow();
        }

        // Fetch application
        Application app = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Application not found: " + applicationId));

        // Fetch job
        Jobs job = jobRepository.findById(app.getJobId())
                .orElseThrow(() -> new ResourceNotFoundException("Job not found: " + app.getJobId()));

        // Fetch candidate — app.userId stores the candidate's EMAIL
        User candidate = userRepository.findByEmail(app.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Candidate not found: " + app.getUserId()));

        // Fetch recruiter by the recruiter's MongoDB ID using raw collection query to bypass Spring Data's String ID type conversions
        org.bson.Document recruiterDoc = mongoTemplate.getCollection("users")
                .find(new org.bson.Document("_id", new ObjectId(job.getRecruiterId())))
                .first();
        if (recruiterDoc == null) {
            throw new ResourceNotFoundException("Recruiter not found: " + job.getRecruiterId());
        }
        User recruiter = mongoTemplate.getConverter().read(User.class, recruiterDoc);

        // Build and save the room
        ChatRoom room = new ChatRoom();
        room.setApplicationId(applicationId);
        room.setJobId(job.getId());
        room.setJobTitle(job.getTitle());

        room.setCandidateId(candidate.getId());
        room.setCandidateEmail(candidate.getEmail());
        room.setCandidateName(candidate.getFullName());

        room.setRecruiterId(recruiter.getId());
        room.setRecruiterEmail(recruiter.getEmail());
        room.setRecruiterName(recruiter.getFullName());

        return chatRoomRepository.save(room);
    }

    // ── 2. Get chat rooms for logged-in user ─────────────────────────────────

    public List<ChatRoom> getMyChatRooms(String userEmail) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userEmail));

        if ("RECRUITER".equals(user.getRole())) {
            List<ChatRoom> rooms = chatRoomRepository
                    .findByRecruiterIdOrderByLastMessageAtDesc(user.getId());
            return rooms != null ? rooms : new ArrayList<>();
        } else {
            List<ChatRoom> rooms = chatRoomRepository
                    .findByCandidateIdOrderByLastMessageAtDesc(user.getId());
            return rooms != null ? rooms : new ArrayList<>();
        }
    }

    // ── 3. Get messages for a chat room (also marks unread as read) ──────────

    public List<ChatMessage> getChatMessages(String chatRoomId, String userEmail) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userEmail));

        ChatRoom room = chatRoomRepository.findById(chatRoomId)
                .orElseThrow(() -> new ResourceNotFoundException("Chat room not found: " + chatRoomId));

        // Auth check — user must be a participant
        if (!room.getCandidateId().equals(user.getId())
                && !room.getRecruiterId().equals(user.getId())) {
            throw new ForbiddenException("You are not a participant of this chat room.");
        }

        // Mark messages from the other party as read
        List<ChatMessage> unread = chatMessageRepository
                .findByChatRoomIdAndReadFalseAndSenderIdNot(chatRoomId, user.getId());
        unread.forEach(m -> m.setRead(true));
        if (!unread.isEmpty()) {
            chatMessageRepository.saveAll(unread);
        }

        return chatMessageRepository.findByChatRoomIdOrderBySentAtAsc(chatRoomId);
    }

    // ── 4. Send a message ────────────────────────────────────────────────────

    public ChatMessage sendMessage(String chatRoomId, String content, String userEmail) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userEmail));

        ChatRoom room = chatRoomRepository.findById(chatRoomId)
                .orElseThrow(() -> new ResourceNotFoundException("Chat room not found: " + chatRoomId));

        // Auth check
        if (!room.getCandidateId().equals(user.getId())
                && !room.getRecruiterId().equals(user.getId())) {
            throw new ForbiddenException("You are not a participant of this chat room.");
        }

        // Validate content
        if (content == null || content.isBlank()) {
            throw new BadRequestException("Message content must not be empty.");
        }
        if (content.length() > 2000) {
            throw new BadRequestException("Message content must not exceed 2000 characters.");
        }

        // Build and persist the message
        ChatMessage msg = new ChatMessage();
        msg.setChatRoomId(chatRoomId);
        msg.setSenderId(user.getId());
        msg.setSenderEmail(user.getEmail());
        msg.setSenderName(user.getFullName());
        msg.setSenderRole(user.getRole());
        msg.setContent(content.trim());
        ChatMessage saved = chatMessageRepository.save(msg);

        // Update the chat room's preview
        room.setLastMessageAt(LocalDateTime.now());
        String preview = content.trim();
        room.setLastMessagePreview(preview.length() > 50 ? preview.substring(0, 50) : preview);
        chatRoomRepository.save(room);

        return saved;
    }

    // ── 5. Get total unread message count for logged-in user ─────────────────

    public int getUnreadCount(String userEmail) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userEmail));

        List<ChatRoom> rooms = getMyChatRooms(userEmail);
        int total = 0;
        for (ChatRoom room : rooms) {
            total += chatMessageRepository
                    .countByChatRoomIdAndReadFalseAndSenderIdNot(room.getId(), user.getId());
        }
        return total;
    }

    // ── Helper — Get single room by ID (used by WebSocket controller) ─────────

    public ChatRoom getChatRoomById(String chatRoomId) {
        return chatRoomRepository.findById(chatRoomId)
                .orElseThrow(() -> new ResourceNotFoundException("Chat room not found: " + chatRoomId));
    }

    public ChatRoom getChatRoom(String chatRoomId, String userEmail) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userEmail));

        ChatRoom room = chatRoomRepository.findById(chatRoomId)
                .orElseThrow(() -> new ResourceNotFoundException("Chat room not found: " + chatRoomId));

        if (!room.getCandidateId().equals(user.getId()) && !room.getRecruiterId().equals(user.getId())) {
            throw new ForbiddenException("You are not a participant of this chat room.");
        }
        return room;
    }
}
