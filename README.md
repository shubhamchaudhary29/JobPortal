# 🚀 JobPortal

<div align="center">

A modern **Full-Stack Recruitment Platform** that connects recruiters with talented candidates through a secure, scalable, and real-time hiring experience.

Built using **Spring Boot**, **React**, **MongoDB**, **Docker**, and **WebSockets**, JobPortal provides everything from authentication and resume management to real-time recruiter-candidate communication.

![Java](https://img.shields.io/badge/Java-21-red)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen)
![React](https://img.shields.io/badge/React-19-61DAFB)
![MongoDB](https://img.shields.io/badge/MongoDB-Database-green)
![Docker](https://img.shields.io/badge/Docker-Containerized-blue)
![JWT](https://img.shields.io/badge/JWT-Authentication-orange)
![WebSocket](https://img.shields.io/badge/WebSocket-Real--Time-purple)

</div>

---

# ✨ Features

## 👨‍💻 Candidate Features

- Secure Registration & Login
- JWT Authentication
- Create & Manage Profile
- Upload Resume (PDF)
- Browse All Jobs
- Search Jobs
- Filter by Location
- Apply to Jobs
- Track Applications
- Chat with Recruiters
- Real-Time Messaging
- Responsive Dashboard

---

## 💼 Recruiter Features

- Recruiter Authentication
- Create Job Listings
- Edit Job Listings
- Delete Job Listings
- View Posted Jobs
- Manage Applicants
- Download Candidate Resume
- Real-Time Chat
- Applicant Dashboard

---

## 💬 Real-Time Chat

One of the major features of JobPortal is the built-in messaging system.

Features include:

- Recruiter ↔ Candidate Messaging
- WebSocket Communication
- STOMP Protocol
- Instant Message Delivery
- Unread Message Counter
- Conversation History
- Persistent Chat Storage

---

## 🔐 Authentication & Security

- Spring Security
- JWT Authentication
- BCrypt Password Encryption
- Role-Based Access Control (RBAC)
- Protected REST APIs
- Secure Resume Access
- CORS Configuration

---

# 🛠 Tech Stack

## Frontend

- React (Vite)
- React Router
- Tailwind CSS
- Axios

---

## Backend

- Java 21
- Spring Boot 3
- Spring Security
- Spring Data MongoDB
- JWT
- WebSocket (STOMP)

---

## Database

- MongoDB

---

## DevOps

- Docker
- Docker Compose

---

# 📂 Project Structure

```
JobPortal
│
├── backend
│   ├── config
│   ├── controller
│   ├── dto
│   ├── entity
│   ├── repository
│   ├── security
│   ├── service
│   ├── websocket
│   └── resources
│
├── frontend
│   ├── components
│   ├── pages
│   ├── services
│   ├── hooks
│   ├── context
│   └── assets
│
├── docker-compose.yml
├── Dockerfile.backend
├── Dockerfile.frontend
└── README.md
```

---

# 🚀 Getting Started

## Clone Repository

```bash
git clone https://github.com/shubhamchaudhary29/JobPortal.git

cd JobPortal
```

---

# 🐳 Running with Docker

Start the complete application using Docker Compose.

```bash
docker compose up --build
```

This launches:

- Frontend
- Backend
- MongoDB

No manual installation is required beyond Docker.

---

# 💻 Running Locally

## Backend

```bash
cd backend

./mvnw spring-boot:run
```

Runs on

```
http://localhost:8080
```

---

## Frontend

```bash
cd frontend

npm install

npm run dev
```

Runs on

```
http://localhost:5173
```

---

# 📄 Resume Management

Candidates can upload resumes directly while applying.

Recruiters can:

- View Applicants
- Download Resume
- Review Applications

---

# 💬 Messaging Workflow

```
Candidate
     │
     │ Apply
     ▼
Recruiter

     │
     │ Start Chat
     ▼

WebSocket Server

     ▲
     │
Instant Messaging
```

---

# 🔐 Security Architecture

```
Client

↓

JWT Login

↓

Spring Security Filter

↓

Authentication

↓

Role Verification

↓

Protected APIs
```

---

# 🌐 REST APIs

The backend exposes REST APIs for:

- Authentication
- Users
- Recruiters
- Jobs
- Applications
- Chat
- Resume Management

---

# 🐳 Docker Architecture

```
             Docker Compose
                    │
        ┌───────────┼───────────┐
        │           │           │
        ▼           ▼           ▼
   React App   Spring Boot   MongoDB
```

---

# 🚀 Future Improvements

- Email Notifications
- AI Resume Screening
- Resume Builder
- Company Profiles
- Saved Jobs
- Job Recommendations
- Admin Dashboard
- Interview Scheduling
- Analytics Dashboard
- Resume Parsing

---

# 👨‍💻 Author

**Shubham Chaudhary**

GitHub:
https://github.com/shubhamchaudhary29

---

# ⭐ Support

If you found this project useful,

⭐ Star the repository.

Contributions, issues, and feature requests are always welcome.
