<div align="center">

# 🚀 JobPortal

### Modern Full-Stack Recruitment Platform with Real-Time Chat & Multi-Platform Job Aggregation

<p align="center">
Connecting <b>Recruiters</b> and <b>Candidates</b> through a secure, scalable, and modern recruitment platform powered by <b>Spring Boot</b>, <b>React</b>, <b>MongoDB</b>, <b>Docker</b>, and <b>WebSockets</b>.
</p>

<p align="center">

![Java](https://img.shields.io/badge/Java-21-red?style=for-the-badge&logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen?style=for-the-badge&logo=springboot)
![React](https://img.shields.io/badge/React-19-61DAFB?style=for-the-badge&logo=react)
![MongoDB](https://img.shields.io/badge/MongoDB-Database-green?style=for-the-badge&logo=mongodb)
![Docker](https://img.shields.io/badge/Docker-Containerized-blue?style=for-the-badge&logo=docker)
![JWT](https://img.shields.io/badge/JWT-Authentication-orange?style=for-the-badge)
![WebSocket](https://img.shields.io/badge/WebSocket-RealTime-purple?style=for-the-badge)

</p>

---

### 🌐 Live Demo

Coming Soon

### 📽 Demo Video

Coming Soon

### 📄 Documentation

See [Backend architecture and API v1](docs/architecture.md).

</div>

---

# 📖 Overview

**JobPortal** is a modern full-stack recruitment platform that streamlines the hiring process for both **Recruiters** and **Candidates**.

Unlike conventional job portals that only display jobs created within their own application, **JobPortal combines recruiter-posted jobs with thousands of external opportunities aggregated from multiple leading job platforms using the Adzuna Job Search API.**

The platform provides:

- 🔐 Secure JWT Authentication
- 💼 Recruiter Dashboard
- 👨‍💻 Candidate Dashboard
- 📄 Resume Upload
- 💬 Real-Time Recruiter ↔ Candidate Chat
- 🌍 Aggregated Jobs from Multiple Platforms
- 🐳 Dockerized Deployment
- ⚡ Modern Responsive UI

---

# ✨ Key Highlights

- 🔒 Secure Role-Based Authentication
- 🌍 Search Jobs from Multiple Platforms
- 💬 Real-Time Chat using WebSockets
- 📄 Resume Upload & Download
- 🐳 Fully Dockerized Application
- 📱 Responsive User Interface
- ⚡ RESTful Backend APIs
- 🔑 JWT Authentication
- 👥 Recruiter & Candidate Dashboards
- 🔍 Smart Job Search
- 📂 Modular Codebase
- 🚀 Production Ready Architecture

---

# 🌍 Multi-Platform Job Aggregation

One of the biggest highlights of **JobPortal** is its ability to aggregate jobs from multiple recruitment platforms.

Instead of limiting candidates to only jobs posted inside the application, JobPortal integrates with the **Adzuna Job Search API**, allowing users to discover opportunities from multiple popular job platforms through a single interface.

## Supported Platforms

- 💼 LinkedIn
- 🔵 Indeed
- 🟢 Glassdoor
- 🟣 ZipRecruiter
- 🟠 Reed
- 🌍 Adzuna Partner Network
- 📰 Various Global Job Boards

> The available job sources depend on the results returned by the Adzuna API for the selected country and search query.

### Benefits

- Search thousands of jobs instantly
- Unified job discovery experience
- Search by keywords
- Filter by location
- Browse external opportunities
- Access recruiter-posted jobs from the same platform

This transforms JobPortal into a **centralized recruitment hub** instead of just another CRUD job board.

---

# 👨‍💻 Candidate Features

### Authentication

- Secure Registration
- Secure Login
- JWT Authentication
- Password Encryption
- Role-Based Authorization

### Profile

- Create Candidate Profile
- Edit Personal Information
- Upload Resume (PDF)
- Resume Management

### Job Search

- Browse All Jobs
- Search by Job Title
- Search by Keywords
- Filter by Location
- View Job Details
- Explore External Jobs via Adzuna

### Applications

- One Click Apply
- Track Applied Jobs
- View Application Status
- Resume Submission

### Communication

- Real-Time Recruiter Chat
- Instant Messaging
- Conversation History
- Unread Message Notifications

---

# 🏢 Recruiter Features

### Dashboard

- Recruiter Dashboard
- Manage Posted Jobs
- View Job Statistics

### Job Management

- Create Jobs
- Edit Jobs
- Delete Jobs
- Manage Listings

### Applicant Management

- View Applicants
- Download Candidate Resume
- Review Applications
- Manage Hiring Process

### Communication

- Chat with Candidates
- Real-Time Messaging
- Persistent Conversations

---

# 💬 Real-Time Chat

JobPortal includes an integrated messaging system that allows recruiters and candidates to communicate directly.

## Features

- WebSocket Communication
- STOMP Messaging
- Instant Message Delivery
- Conversation History
- Recruiter ↔ Candidate Chat
- Persistent Storage
- Unread Message Tracking

This eliminates the need for third-party communication platforms and keeps the hiring process inside the application.

---

# 🔐 Security Features

- Spring Security
- JWT Authentication
- BCrypt Password Encryption
- Stateless Authentication
- Protected REST APIs
- Role-Based Access Control
- Secure Resume Access
- CORS Configuration
- Authentication Filters

---

# 🛠 Technology Stack

## Frontend

- React (Vite)
- React Router DOM
- Tailwind CSS
- Axios
- Context API

---

## Backend

- Java 21
- Spring Boot 3.x
- Spring Security
- Spring Data MongoDB
- JWT
- WebSocket
- STOMP
- Maven

---

## Database

- MongoDB

---

## DevOps

- Docker
- Docker Compose

---

## Third-Party Integrations

- Adzuna Job Search API
- JWT
- WebSocket (STOMP)

---


# ⭐ Why JobPortal?

Unlike traditional academic CRUD projects, JobPortal focuses on solving real-world recruitment challenges by combining:

- Internal recruiter job postings
- External job aggregation
- Resume management
- Secure authentication
- Real-time communication
- Containerized deployment
- Modern UI/UX

making it a scalable and production-oriented recruitment platform.

---

# 🚀 Getting Started

Follow the steps below to run JobPortal on your local machine.

---

# 📋 Prerequisites

Before you begin, ensure you have the following installed:

| Software | Version |
|----------|---------|
| Java | 21+ |
| Node.js | 18+ |
| Maven | Latest |
| MongoDB | Latest |
| Docker | Latest |
| Docker Compose | Latest |
| Git | Latest |

---

# 📥 Clone Repository

```bash
git clone https://github.com/shubhamchaudhary29/JobPortal.git

cd JobPortal
```

---

# ⚙️ Running Locally

Create a private environment file first:

```bash
cp .env.example .env
```

Replace every `REPLACE_WITH_...` placeholder in `.env`. These values are backend-only; never prefix JWT, Adzuna, or MongoDB credentials with `VITE_`, because Vite exposes those variables to browser code.

## 1️⃣ Backend

Navigate to the backend directory.

```bash
cd backend/backend
set -a
source ../../.env
set +a
```

Start the Spring Boot application.

```bash
./mvnw spring-boot:run
```

Backend runs on:

```
http://localhost:8080
```

---

## 2️⃣ Frontend

Navigate to the frontend directory.

```bash
cd frontend
```

Install dependencies.

```bash
npm install
```

Run the development server.

```bash
npm run dev
```

Frontend runs on:

```
http://localhost:5173
```

---

# 🌍 Environment Variables

### Backend

Example configuration:

```properties
spring.data.mongodb.uri=mongodb://localhost:27017/jobportal

jwt.secret=YOUR_SECRET_KEY

adzuna.app.id=YOUR_ADZUNA_APP_ID

adzuna.app.key=YOUR_ADZUNA_APP_KEY
```

---

### Frontend

The browser uses same-origin `/api/v1` and `/ws` paths through the Vite/nginx proxy. Backend secrets must never
use a `VITE_*` prefix because Vite exposes such values to browser code.

---

# 🐳 Running with Docker

The entire application has been fully containerized using Docker.

After creating `.env` as shown above, run:

```bash
docker compose up --build
```

Docker Compose automatically starts:

- Frontend
- Backend
- MongoDB

without requiring any manual configuration.

To stop the application:

```bash
docker compose down
```

MongoDB is reachable only through the internal Compose network. If a local database client needs port 27017, use a development-only override bound to `127.0.0.1`; do not publish that port in production.

Before committing, confirm private configuration and uploads are not tracked:

```bash
git check-ignore .env
git ls-files '.env' 'uploads/**' '**/uploads/**'
```

The first command should identify `.env`; the second should produce no output.

---

# 🐳 Docker Architecture

```
                    Docker Compose

                           │

        ┌──────────────────┼──────────────────┐

        │                  │                  │

        ▼                  ▼                  ▼

 React Frontend      Spring Boot API      MongoDB

        │                  │

        └──────────REST APIs───────────────┘

                           │

                     WebSocket Server

                           │

                    Real-Time Messaging
```

---

# 🏗 System Architecture

```
                 +----------------------+

                 |     React Frontend   |

                 +----------+-----------+

                            |

                       REST API

                            |

                 +----------▼-----------+

                 |   Spring Boot API    |

                 +----------+-----------+

                            |

       ┌────────────────────┼────────────────────┐

       │                    │                    │

       ▼                    ▼                    ▼

 MongoDB Database      JWT Security      WebSocket Server

                                                │

                                                ▼

                                      Real-Time Chat
```

---

# 📂 Project Structure

```
JobPortal

│

├── backend/backend/src/main/java/com/example/backend

│   ├── auth

│   ├── user

│   ├── job

│   ├── application

│   ├── messaging

│   ├── integration/adzuna

│   └── shared

│

├── frontend

│   ├── assets

│   ├── components

│   ├── pages

│   ├── hooks

│   ├── services

│   ├── context

│   ├── routes

│   └── utils

│

├── docker-compose.yml

├── Dockerfile.backend

├── Dockerfile.frontend

└── README.md
```

---

# 👨‍💻 Candidate Workflow

```
Register

    │

Login

    │

Create Profile

    │

Upload Resume

    │

Browse Jobs

    │

Search / Filter

    │

View Job Details

    │

Apply

    │

Track Applications

    │

Chat with Recruiter
```

---

# 🏢 Recruiter Workflow

```
Register

    │

Login

    │

Recruiter Dashboard

    │

Create Job

    │

Manage Listings

    │

View Applicants

    │

Download Resume

    │

Chat with Candidate
```

---

# 💬 Real-Time Chat Workflow

```
Candidate

     │

     │ Sends Message

     ▼

Spring WebSocket Server

     │

     ▼

Message Broker (STOMP)

     │

     ▼

Recruiter

     │

     ▼

Conversation Saved

     │

     ▼

Unread Count Updated
```

---

# 🌍 Job Search Flow

```
Candidate Search

        │

        ▼

Search Query

        │

        ▼

Backend Service

        │

        ├──────── Local Jobs

        │

        └──────── Adzuna API

                      │

                      ▼

      LinkedIn

      Indeed

      Glassdoor

      ZipRecruiter

      Reed

      Other Sources

              │

              ▼

Unified Results

              │

              ▼

Displayed to Candidate
```

---

# 📄 Resume Management

Candidates can upload resumes while creating their profile or applying for jobs.

Recruiters can:

- Download resumes
- Review candidate profiles
- Shortlist applicants

Supported format:

- PDF

---

# 🔐 Authentication Flow

```
User Login

      │

      ▼

Spring Security

      │

      ▼

Authentication Manager

      │

      ▼

JWT Generated

      │

      ▼

Held in Browser Memory

      │

      ▼

JWT Attached to Every Request

      │

      ▼

Protected REST APIs
```

The current flow uses a short-lived access token held only in browser memory and an opaque rotating refresh token in an `HttpOnly` cookie. On page reload the frontend calls `POST /api/v1/auth/sessions/refresh`; concurrent `401` responses share one refresh request and retry once. Logout revokes the active refresh record and expires the cookie. Production must use HTTPS with `REFRESH_COOKIE_SECURE=true`. Refresh-token records store SHA-256 hashes only and expire through a MongoDB TTL index.

Authentication settings are documented in `.env.example`: `JWT_ACCESS_TOKEN_MINUTES`, `REFRESH_TOKEN_DAYS`, cookie settings, and bounded login-rate-limit settings. The rate limiter is intentionally single-instance; multi-instance deployments need a shared limiter before horizontal scaling.

Resume uploads are limited by `RESUME_MAX_BYTES`, require both PDF MIME type and PDF signatures, use random storage names, and are available only through authorized downloads.

Before enabling unique indexes against existing production data, audit without deleting anything:

```javascript
db.users.aggregate([{ $group: { _id: { $toLower: "$email" }, ids: { $push: "$_id" }, count: { $sum: 1 } } }, { $match: { count: { $gt: 1 } } }])
db.applications.aggregate([{ $group: { _id: { userId: "$userId", jobId: "$jobId" }, ids: { $push: "$_id" }, count: { $sum: 1 } } }, { $match: { count: { $gt: 1 } } }])
db.chat_rooms.aggregate([{ $group: { _id: "$applicationId", ids: { $push: "$_id" }, count: { $sum: 1 } } }, { $match: { _id: { $ne: null }, count: { $gt: 1 } } }])
```

Existing application status `UNDER_REVIEW` must be migrated idempotently to `IN_REVIEW` before deployment:

```javascript
db.applications.updateMany({ status: "UNDER_REVIEW" }, { $set: { status: "IN_REVIEW" } })
db.users.find().forEach(function(user) { var normalized = user.email.trim().toLowerCase(); if (normalized !== user.email) db.users.updateOne({ _id: user._id }, { $set: { email: normalized } }); })
```

Run backend verification with `cd backend/backend && ./mvnw clean verify`; run frontend checks with `cd frontend && npm run lint && npm test && npm run build`; validate and start the stack with `docker compose config` and `docker compose up --build` after configuring `.env`.

---

# 🛡 Authorization

The application implements Role-Based Access Control (RBAC).

### Candidate

✔ Browse Jobs

✔ Apply

✔ Upload Resume

✔ Chat

✔ Manage Profile

---

### Recruiter

✔ Create Jobs

✔ Edit Jobs

✔ Delete Jobs

✔ View Applicants

✔ Download Resume

✔ Chat

---

# 📦 File Storage

Candidate resumes are securely stored and associated with their respective applications.

Recruiters can download resumes directly from the Applicant Dashboard.

---

# 🌐 REST API Overview

The canonical base path is `/api/v1`. OpenAPI JSON is available at `/v3/api-docs` and local Swagger UI at `/swagger-ui.html`; set `SWAGGER_ENABLED=false` in production. Pagination, route migration, errors, supported filters, and sort allowlists are documented in [docs/architecture.md](docs/architecture.md).

The backend exposes RESTful APIs for:

- Authentication
- User Management
- Recruiter Management
- Candidate Profiles
- Job Listings
- Applications
- Resume Management
- Chat System
- External Job Aggregation
- Adzuna Integration

---

---

# 📚 API Overview

The backend follows a RESTful architecture and exposes well-structured endpoints for authentication, user management, job management, applications, resume handling, chat, and external job aggregation.

| Module | Description |
|---------|-------------|
| Authentication | User registration, login, JWT authentication |
| User Profile | Create and manage candidate profiles |
| Recruiter | Recruiter dashboard and job management |
| Jobs | CRUD operations for job postings |
| Applications | Apply for jobs and manage applications |
| Resume | Upload and download candidate resumes |
| Chat | Real-time recruiter-candidate messaging |
| External Jobs | Fetch jobs from Adzuna Job Search API |

---

# 🌐 Third-Party Integrations

## 🔍 Adzuna Job Search API

JobPortal integrates with the **Adzuna Job Search API** to provide candidates with access to thousands of external job opportunities.

### Features

- Search jobs from multiple platforms
- Keyword-based search
- Location-based search
- Real-time job listings
- Unified search experience
- External application links

### Job Sources

- LinkedIn
- Indeed
- Glassdoor
- ZipRecruiter
- Reed
- Adzuna Partner Network
- Other supported job boards

---

# 💾 Database Overview

MongoDB is used as the primary database for storing application data.

### Main Collections

```
Users

Recruiters

Jobs

Applications

Messages

Conversations

Profiles
```

Each collection is designed to maintain a clean separation of responsibilities while ensuring scalability and efficient querying.

---

# 🔄 Application Flow

```
                     Recruiter

                          │

                     Create Job

                          │

                          ▼

                 Stored in Database

                          │

                          ▼

              Candidate Searches Jobs

                          │

                          ▼

       ┌────────────────────────────────┐

       │ Local Jobs + Adzuna API Results │

       └────────────────────────────────┘

                          │

                          ▼

                  Candidate Applies

                          │

                          ▼

                  Resume Uploaded

                          │

                          ▼

               Recruiter Reviews Candidate

                          │

                          ▼

                Recruiter Starts Chat

                          │

                          ▼

                 Hiring Communication
```

---

# ⚡ Performance Highlights

- Stateless JWT Authentication
- RESTful Architecture
- Real-Time WebSocket Communication
- Modular Project Structure
- Scalable Backend Services
- Dockerized Deployment
- Fast React Frontend
- MongoDB NoSQL Storage

---

# 🔐 Security Features

The application follows modern backend security practices.

- JWT Authentication
- BCrypt Password Encryption
- Spring Security
- Stateless Authentication
- Role-Based Access Control
- Protected REST APIs
- Secure Resume Access
- CORS Configuration
- Authentication Filters
- Input Validation

---

# 🧪 Testing

The backend suite includes JUnit, Spring MVC, service, mapper, security, OpenAPI, pagination, and ArchUnit tests. The frontend uses Vitest and ESLint. See [docs/architecture.md](docs/architecture.md#commands) for the repository-controlled commands.

---

# 🚀 Future Roadmap

The following features are planned for future releases.

## AI Features

- AI Resume Analyzer
- Resume Score Prediction
- Skill Gap Analysis
- Resume Parsing
- AI Job Recommendations
- AI Cover Letter Generator

---

## Recruiter Features

- Company Profiles
- Recruiter Analytics
- Candidate Shortlisting
- Interview Scheduling
- Offer Management

---

## Candidate Features

- Saved Jobs
- Job Alerts
- Email Notifications
- Bookmark Companies
- Profile Verification

---

## Platform Features

- Admin Dashboard
- Multi-language Support
- OAuth Login
- Email Verification
- Password Reset
- Notification Center
- Dark Mode
- Progressive Web App (PWA)

---

# 🤝 Contributing

Contributions are welcome!

If you would like to improve JobPortal:

1. Fork the repository.
2. Create a new feature branch.

```bash
git checkout -b feature/my-feature
```

3. Commit your changes.

```bash
git commit -m "Add amazing feature"
```

4. Push your branch.

```bash
git push origin feature/my-feature
```

5. Open a Pull Request.

---

# 📝 License

This project is licensed under the MIT License.

Feel free to use, modify, and distribute this project under the terms of the license.

---

# 🙏 Acknowledgements

Special thanks to the amazing open-source community and technologies that made this project possible.

- Spring Boot
- React
- MongoDB
- Docker
- Tailwind CSS
- WebSocket (STOMP)
- JWT
- Maven
- Axios
- Adzuna Job Search API

---

# 👨‍💻 Author

## Shubham Chaudhary

Computer Science Engineering Student passionate about Backend Development, Distributed Systems, and Full-Stack Web Applications.

### Connect with me

- GitHub: https://github.com/shubhamchaudhary29
- LinkedIn: *(Add your LinkedIn profile here)*

---

# ⭐ Show Your Support

If you found this project helpful or interesting:

⭐ Star this repository

🍴 Fork the repository

🛠️ Contribute to the project

📢 Share it with others

Your support motivates future development and improvements.

---

<div align="center">

## 🚀 Built with ❤️ using Spring Boot, React, MongoDB, Docker & WebSockets

### If you like this project, don't forget to ⭐ star the repository!

</div>
