# iLink — University Competition Team Building & Achievement Showcase Platform

> An intelligent team building and collaboration platform based on Spring Boot + Thymeleaf

## Overview

iLink is an all-in-one competition service platform for university students and faculty, creating a complete ecosystem of **"Find Teammates — Find Projects — Find Mentors"**.

### Design Style

macOS liquid glass effect + black/white/gray minimalist aesthetic
- Glass-morphism cards: `backdrop-filter: blur(20px) saturate(1.8)`
- Unified palette: Primary `#111827`, Gray `#6b7280`
- Smooth animations: `cubic-bezier(0.4, 0, 0.2, 1)`
- Particle background effect complementing card glassmorphism

---

## Feature Modules

### 1. User System (Authentication, Registration, Profile)

Multi-factor login support (phone number / student ID / username) with progressive rate limiting. Registration enforces identity selection (Student / Teacher / Admin), password strength policy, and duplicate detection.

**Entity:** `User` — id, username, studentId, phoneNumber, password, email, role (STUDENT/TEACHER/ADMIN), avatar, realName, gender, grade, major, school, college, bio, honors, createdAt
<img width="1209" height="945" alt="image" src="https://github.com/user-attachments/assets/1d5b1d97-4c6d-4cb3-bb2e-e9ae5070b2fc" /><img width="998" height="1135" alt="image" src="https://github.com/user-attachments/assets/32a6ac55-f7cf-4706-a002-ce75d991ead7" />


| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/login` | POST | Multi-mode login with fail-lock protection |
| `/api/register` | POST | Registration (rate-limited: 3/min) |
| `/api/logout` | GET/POST | Logout |
| `/api/user/profile` | GET | Get current user profile |
| `/api/user/profile` | POST | Update profile (name, avatar, grade, major, school, bio, honors) |
| `/api/user/password` | PUT | Change password |
| `/api/user/public/{userId}` | GET | Public user overview (with latest 20 posts) |

**Pages:** `index.html`, `login.html`, `register.html`, `profile.html`, `profile-edit.html`, `profile-password.html`, `profile-honors.html`, `profile-posts.html`, `profile-favorites.html`, `profile-article-edit.html`, `user-profile.html`

---

### 2. Smart Recommendation Engine

Skill-tag-based intelligent matching algorithm. Recommends the most suitable teams to users, and the best-fit members to team leaders. Records recommendation feedback for continuous model optimization.
<img width="1950" height="800" alt="image" src="https://github.com/user-attachments/assets/35e6b5c6-7cb2-41b5-b8e4-bc274972c4c3" />

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/recommendations/teams` | GET | Recommend teams to a user (skill-based matching) |
| `/api/recommendations/users` | GET | Recommend members to a team (skill-based matching) |
| `/api/recommendations/match` | GET | Calculate match score between user and team |
| `/api/recommendations/feedback/{logId}` | POST | Record recommendation feedback for model tuning |

---

### 3. Team Building (Recruitment & Matching)

Full lifecycle management for team recruitment: publish demands → browse marketplace → apply → approve/reject → form team. Supports keyword search, category filtering, and status tracking.
<img width="1942" height="810" alt="QQ_1782284692365" src="https://github.com/user-attachments/assets/edc84ad6-f0c3-40aa-b062-2206191b2b18" />
**Status flow:** `OPEN` (recruiting) → `TEAMING` (forming) → `CLOSED` (ended)

**Entities:**
- `TeamDemand` — id, title, description, competitionId, requiredSkills, requiredMemberCount, deadline, status, creatorId
- `TeamApplication` — id, teamId, userId, status (PENDING/APPROVED/REJECTED), message

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/team/list` | GET | Team list (paginated, search, filter by category/status) |
| `/api/team/{id}` | GET | Team detail (creator info, members, application count) |
| `/api/team` | POST | Publish recruitment demand |
| `/api/team/{id}` | PUT | Edit demand (creator only, OPEN status only) |
| `/api/team/{id}` | DELETE | Delete demand (creator only, no applications) |
| `/api/team/{id}/status` | PUT | Update status (OPEN→TEAMING, or →CLOSED) |
| `/api/team/join` | POST | Apply to join team |
| `/api/team/application-status` | GET | Check user's application status |
| `/api/team/application/{id}/approve` | PUT | Approve/reject application (auto-close when full) |
| `/api/team/my/published` | GET | My published demands |
| `/api/team/my/applications` | GET | My submitted applications |
| `/api/team/my/pending-applications` | GET | Pending applications (creator view) |
| `/api/team/{id}/members` | GET | Team member list |

---

### 4. Team Workspace & Task Collaboration

Four-column Kanban board (**To Do → In Progress → Review → Done**) with full drag-and-drop support. Task assignment, submission, review workflow built-in. Supports nested comments and file attachments.

**Entities:**
- `TeamTask` — id, teamId, taskTitle, taskDescription, taskType (DEVELOPMENT/DESIGN/TESTING/DOCUMENTATION/OTHER), priority (LOW/MEDIUM/HIGH/URGENT), status (PENDING/IN_PROGRESS/REVIEW/COMPLETED/CANCELLED), estimatedHours, actualHours, deadline, assignedTo, createdBy
- `TaskParticipant` — taskId, userId, role (OWNER/LEAD/MEMBER/REVIEWER), contributionHours, contributionRate
- `TaskSubmission` — taskId, submitterId, content, attachments
- `TaskComment` — taskId, parentId (supports threaded replies), userId, content, commentType, attachments, likeCount
<img width="1302" height="792" alt="image" src="https://github.com/user-attachments/assets/e966c064-1321-4072-b44e-67f00c929893" />

| Endpoint | Method | Description |
|----------|--------|-------------|
| `GET /api/tasks?teamId=` | GET | Get team tasks (filtered by role) |
| `GET /api/tasks/{taskId}` | GET | Get task detail |
| `POST /api/team/{teamId}/tasks` | POST | Create task (team lead only) |
| `PUT /api/tasks/{taskId}` | PUT | Update task |
| `PUT /api/tasks/{taskId}/status` | PUT | Update task status |
| `DELETE /api/tasks/{taskId}` | DELETE | Delete task (team lead only) |
| `PUT /api/tasks/{taskId}/assign` | PUT | Assign task to member |
| `GET /api/tasks/{taskId}/participants` | GET | Get task participants |
| `POST /api/tasks/{taskId}/participants` | POST | Add participant |
| `DELETE /api/tasks/{taskId}/participants/{userId}` | DELETE | Remove participant |
| `GET /api/tasks/{taskId}/comments` | GET | Get task comments (threaded) |
| `POST /api/tasks/{taskId}/comments` | POST | Add comment (supports parentId for replies) |
| `PUT /api/tasks/{taskId}/review` | PUT | Review submission (approve→COMPLETED / reject→IN_PROGRESS) |
| `GET /api/tasks/{taskId}/submissions` | GET | Get submissions (role-filtered) |
| `POST /api/tasks/{taskId}/submit` | POST | Submit task (auto-switches to REVIEW status) |

---

### 5. Project Milestones

Track team project progress with named milestones, completion rates, deliverables, and status tracking.

**Entity:** `ProjectMilestone` — id, teamId, milestoneName, milestoneDescription, dueDate, completedDate, completionRate, deliverables, status (PENDING/IN_PROGRESS/COMPLETED/DELAYED)

| Endpoint | Method | Description |
|----------|--------|-------------|
| `GET /api/team/{teamId}/milestones` | GET | Get team milestones |
| `GET /api/milestones/{id}` | GET | Get single milestone |
| `POST /api/team/{teamId}/milestones` | POST | Create milestone (team members only) |
| `PUT /api/milestones/{id}` | PUT | Update milestone |
| `PUT /api/milestones/{id}/progress` | PUT | Update progress percentage |
| `DELETE /api/milestones/{id}` | DELETE | Delete milestone |

---

### 6. Real-time Team Chat (WebSocket)

STOMP over WebSocket real-time messaging for team collaboration. Messages persist to database for history retrieval. Authentication enforced on WebSocket connection and subscription.

| Endpoint / Topic | Method | Description |
|------------------|--------|-------------|
| `GET /api/team/{teamId}/messages` | GET | Get chat history (REST fallback) |
| `POST /api/team/{teamId}/messages` | POST | Send message (REST fallback) |
| `/ws` | WebSocket | SockJS STOMP endpoint |
| `/app/chat/{teamId}` | STOMP SEND | Send message → broadcast |
| `/topic/team/{teamId}` | STOMP SUBSCRIBE | Receive team messages |

---

### 7. Community Forum

Full-featured discussion forum with four categories (**General / Tech / Competition / Resource**). Rich-text post editor with HTML sanitization (Jsoup + custom sanitizer). Like, favorite, and nested comment interactions.
<img width="1905" height="903" alt="image" src="https://github.com/user-attachments/assets/ab7ff117-1a5f-48cf-9614-6e22d13083d7" />

**Entities:**
- `CommunityPost` — id, authorId, category, title, content, attachments (JSON), viewCount, likeCount, favoriteCount
- `CommunityComment` — id, postId, userId, content
- `CommunityPostLike` — postId, userId
- `CommunityPostFavorite` — postId, userId

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/community/posts` | GET | Post list (paginated, search, category filter) — public |
| `/api/community/posts/{id}` | GET | Post detail (auto-increments view count) |
| `/api/community/posts` | POST | Create post (HTML sanitized) |
| `/api/community/posts/{id}` | PUT | Edit post (author or admin only) |
| `/api/community/posts/{id}` | DELETE | Delete post (author or admin only) |
| `/api/community/posts/{id}/like` | POST | Toggle like |
| `/api/community/posts/{id}/favorite` | POST | Toggle favorite |
| `/api/community/posts/{postId}/comments` | GET | Get comment list |
| `/api/community/posts/{postId}/comments` | POST | Add comment |
| `/api/community/comments/{commentId}` | DELETE | Delete comment |
| `/api/community/my-posts` | GET | My posts |
| `/api/community/my-favorites` | GET | My favorites |

---

### 8. Achievement Gallery and List of Competitions

Digital showcase for project outcomes, research results, and competition awards. Supports file upload with MIME-type verification. Public gallery with search and sorting (latest / most popular). Cached for performance.
<img width="1818" height="1032" alt="image" src="https://github.com/user-attachments/assets/c17a581c-06a3-45e4-99e7-490a0437e976" />

**Entity:** `Asset` — id, title, description, fileUrl, userId, viewCount, createdAt

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/asset/list` | GET | Asset list (paginated, search, category filter, sort) |
| `/api/asset/{id}` | GET | Asset detail (with publisher info, 30-min cache) |
| `/api/asset/upload` | POST | Upload achievement (title + description + optional file) |
| `/api/asset/{id}` | PUT | Edit achievement (replaces old file if new one uploaded) |
| `/api/asset/download/{id}` | GET | Download achievement file (increments download count) |

---
List of Competitions
<img width="1864" height="1122" alt="image" src="https://github.com/user-attachments/assets/7bef811a-101b-49a0-9b33-cdc12e77a6c7" />

### 9. Mentor Matching

Two-way mentor-student matching system. Teachers apply to become mentors with research directions and projects. Students browse mentor profiles and apply to join projects. Mentor approves or rejects applications.
<img width="1857" height="776" alt="image" src="https://github.com/user-attachments/assets/f743a79d-7832-4feb-80ab-0598c09e424b" />

**Entities:**
- `TeacherApplication` — id, userId, introduction, researchDirection, projects, status (PENDING/APPROVED)
- `ProjectApplication` — id, teacherId, userId, status (PENDING/APPROVED/REJECTED), message

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/teacher/list` | GET | Mentor list (APPROVED only, paginated, search, filter) |
| `/api/teacher/{id}` | GET | Mentor detail (with linked user info, cached) |
| `/api/teacher/apply` | POST | Apply to become a mentor (one per user) |
| `/api/teacher/project-apply` | POST | Student applies to join mentor's project |
| `/api/teacher/project-application-status` | GET | Check student's application status |
| `/api/teacher/my/project-applications` | GET | Mentor's pending project applications |
| `/api/teacher/project-application/{id}/approve` | PUT | Mentor approves/rejects application |

---

### 10. User Skills Tags

Fine-grained skill management for better matching. Each skill includes level, category, certification, experience years, and portfolio link.

**Entity:** `UserSkill` — id, userId, skillName, skillLevel, skillCategory, certification, yearsExperience, portfolioUrl, verified

| Endpoint | Method | Description |
|----------|--------|-------------|
| `GET /api/user/skills` | GET | Get current user's skills |
| `POST /api/user/skills` | POST | Add skill (duplicate prevention) |
| `DELETE /api/user/skills/{skillId}` | DELETE | Delete skill (ownership check) |

---

### 11. Notification Center

Real-time notification system with type categorization. Supports unread count badge, batch mark-as-read, and role-based delivery.

**Entity:** `Notification` — id, userId, type (TEAM_INVITE/TASK_ASSIGNED/TASK_COMPLETED/MILESTONE_UPDATE/RECOMMENDATION/SYSTEM), title, content, isRead, relatedId, relatedType

| Endpoint | Method | Description |
|----------|--------|-------------|
| `GET /api/notifications` | GET | Get notification list (ownership verified) |
| `GET /api/notifications/unread-count` | GET | Get unread count |
| `PUT /api/notifications/{id}/read` | PUT | Mark single as read |
| `PUT /api/notifications/read-all` | PUT | Mark all as read |
| `POST /api/notifications` | POST | Create notification |

---

### 12. Admin Panel

Comprehensive administration dashboard. Full CRUD for users, teams, mentors, assets, and community posts. Role management with self-demotion protection. Cascade cleanup on user deletion.

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/admin/dashboard` | GET | Dashboard statistics |
| `/api/admin/users` | GET | User list |
| `/api/admin/teams` | GET | Team list |
| `/api/admin/teachers` | GET | Mentor list |
| `/api/admin/assets` | GET | Asset list |
| `/api/admin/community-posts` | GET | Post list |
| `/api/admin/user/{id}` | PUT | Edit user |
| `/api/admin/user/{id}/role` | PUT | Change user role (anti-self-demotion) |
| `/api/admin/user/{id}` | DELETE | Delete user (cascade cleanup) |
| `/api/admin/team/{id}` | DELETE | Delete team |
| `/api/admin/teacher/{id}/approve` | PUT | Approve mentor application |
| `/api/admin/teacher/{id}` | DELETE | Delete mentor |
| `/api/admin/asset/{id}` | DELETE | Delete asset |
| `/api/admin/community-post/{id}` | DELETE | Delete post |

---

## Tech Stack

| Layer | Technology | Version |
|-------|-----------|---------|
| Backend | Spring Boot | 2.7.0 |
| Security | Spring Security + BCrypt + CSRF | - |
| Database | MySQL | 5.7+ |
| ORM | MyBatis-Plus | 3.5.x |
| Real-time | WebSocket + STOMP (SockJS) | - |
| Frontend | HTML5 + CSS3 + JavaScript | ES6+ |
| UI Framework | Bootstrap | 5.x |
| Template Engine | Thymeleaf | 3.0.x |
| Cache | Caffeine | 3.x |
| Migration | Flyway | 7.x |
| HTML Sanitizer | Jsoup + custom sanitizer | - |

---

## Quick Start

### Prerequisites

- JDK 17+
- MySQL 5.7+
- Maven 3.9+

### Database Setup

```sql
CREATE DATABASE ilink DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

Update database configuration in `src/main/resources/application-dev.yml`:

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/ilink?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai
    username: your_username
    password: your_password
```

### Build & Run

```bash
mvn clean compile -DskipTests
mvn spring-boot:run

# Or package and run
mvn clean package -DskipTests
java -jar target/ilink-*.jar
```

Visit **http://localhost:8090**

---

## Project Structure

```
src/
├── main/
│   ├── java/cn/ilink/
│   │   ├── config/          # Configuration (Security, WebSocket, Cache, MVC, MyBatis)
│   │   ├── controller/      # REST controllers + page controllers
│   │   ├── service/         # Service layer interfaces
│   │   │   └── impl/        # Service implementations
│   │   ├── mapper/          # MyBatis Mapper interfaces
│   │   ├── entity/          # Entity classes
│   │   ├── dto/             # Data Transfer Objects
│   │   ├── vo/              # View Objects
│   │   ├── exception/       # Exception handling
│   │   └── util/            # Utility classes
│   └── resources/
│       ├── static/          # Static assets (CSS, JS, lib)
│       ├── templates/       # Thymeleaf templates
│       │   └── fragments/   # Shared fragments
│       └── db/migration/    # Flyway migration scripts
└── test/                    # Test code
```

---

## Design System

### CSS Variables

```css
:root {
  --glass-bg: rgba(255, 255, 255, 0.72);
  --glass-blur: 20px;
  --glass-border: rgba(0, 0, 0, 0.08);
  --color-black: #111827;
  --color-primary: #2563eb;
  --color-gray: #6b7280;
  --radius-sm: 8px;
  --radius-md: 12px;
  --radius-lg: 16px;
  --z-base: 0;
  --z-raised: 10;
  --z-dropdown: 100;
  --z-sticky: 200;
  --z-overlay: 300;
  --z-modal: 400;
  --z-popover: 500;
  --z-toast: 600;
}
```

---

## License

Apache License 2.0
