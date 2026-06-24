USE ilink;

CREATE TABLE `project_application` (
    `id` INT PRIMARY KEY AUTO_INCREMENT,
    `teacher_id` INT,
    `user_id` INT,
    `status` ENUM('PENDING', 'APPROVED', 'REJECTED'),
    `message` TEXT,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (`teacher_id`) REFERENCES `teacher_application`(`id`),
    FOREIGN KEY (`user_id`) REFERENCES `user`(`id`)
);