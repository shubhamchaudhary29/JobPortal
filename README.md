```markdown
# 🚀 JobPortal - Full-Stack Recruitment Platform

A modern, responsive, and secure full-stack job board application built with **Spring Boot** and **React**. 

JobPortal connects talented candidates with top recruiters. It features a secure Role-Based Access Control (RBAC) system, allowing Recruiters to post jobs and manage applications, while Candidates can browse opportunities, filter by location/role, and upload their resumes seamlessly.

---

## ✨ Key Features

### 🧑‍💻 For Candidates (Users)
* **Secure Authentication:** JWT-based login and registration.
* **Smart Job Search:** Filter jobs instantly by Title, Keyword, or Location.
* **One-Click Apply:** Upload resumes (PDF) directly to job postings via Multipart file handling.
* **Modern UI/UX:** Premium "Indigo & Slate" theme built with Tailwind CSS.

### 💼 For Recruiters
* **Dedicated Dashboard:** A specialized recruiter view to manage active job listings.
* **Job Creation:** Clean, validated forms to post new opportunities to the platform.
* **Applicant Tracking:** View a list of candidates who applied to specific roles and download their resumes directly from the dashboard.
* **Role-Based Security:** Spring Security ensures only users with the `RECRUITER` role can post jobs or view candidate data.

---

## 🛠️ Tech Stack

**Frontend**
* React.js (Vite)
* Tailwind CSS (Styling & Responsive Design)
* React Router DOM (Navigation)
* Axios (HTTP Client & Interceptors)

**Backend**
* Java 21 & Spring Boot 3.x
* Spring Security & JWT (JSON Web Tokens)
* Spring Data MongoDB
* Maven

**Database & Storage**
* MongoDB (NoSQL Database)
* Local File System (Resume PDF Storage)

---

## 📂 Project Structure

This repository is a monorepo containing both the frontend and backend applications.

```text
JobPortal/
├── backend/                # Spring Boot REST API
│   ├── src/main/java/...   # Java Source Code (Controllers, Services, Repositories, Security)
│   ├── src/main/resources/ # application.properties (MongoDB config)
│   └── pom.xml             # Maven Dependencies
│
└── frontend/               # React Client Application
    ├── src/components/     # Reusable UI Components (Header, Footer, Modals)
    ├── src/pages/          # Application Pages (Home, Jobs, Dashboard, Auth)
    ├── src/services/       # Axios API integrations
    └── package.json        # Node Dependencies

```

---

## 🚀 Getting Started

Follow these steps to run the project locally on your machine.

### Prerequisites

* **Java 21+** installed
* **Node.js** (v18+) installed
* **MongoDB** installed and running locally on `localhost:27017`

### 1. Clone the Repository

```bash
git clone [https://github.com/shubhamchaudhary29/JobPortal.git](https://github.com/shubhamchaudhary29/JobPortal.git)
cd JobPortal

```

### 2. Backend Setup

1. Open a terminal and navigate to the backend folder:
```bash
cd backend

```


2. Verify your MongoDB is running. The backend defaults to `mongodb://localhost:27017/jobportal` (configure in `application.properties` if needed).
3. Run the Spring Boot application:
```bash
./mvnw spring-boot:run

```


*The backend will start on `http://localhost:8080*`

### 3. Frontend Setup

1. Open a new terminal and navigate to the frontend folder:
```bash
cd frontend

```


2. Install the Node dependencies:
```bash
npm install

```


3. Start the Vite development server:
```bash
npm run dev

```


*The frontend will start on `http://localhost:5173*`

---

## 🔒 Security & CORS configuration

This application uses a stateless REST API.

* Passwords are encrypted using `BCryptPasswordEncoder` before saving to MongoDB.
* Sessions are managed via **JWTs** stored securely in the frontend's local storage.
* CORS is explicitly configured in Spring Security to allow cross-origin requests from the React frontend port (`5173`).

---

## 👤 Author

**Shubham Chaudhary**

* GitHub: [@shubhamchaudhary29](https://www.google.com/search?q=https://github.com/shubhamchaudhary29)

Feel free to reach out or open an issue if you have any questions or suggestions!
