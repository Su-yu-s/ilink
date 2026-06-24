USE ilink;

CREATE TABLE `team_application` (
    `id` INT PRIMARY KEY AUTO_INCREMENT,
    `team_id` INT,
    `user_id` INT,
    `status` ENUM('PENDING', 'APPROVED', 'REJECTED'),
    `message` TEXT,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (`team_id`) REFERENCES `team_demand`(`id`),
    FOREIGN KEY (`user_id`) REFERENCES `user`(`id`)
);