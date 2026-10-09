CREATE TABLE IF NOT EXISTS regions (
 id INT PRIMARY KEY, name VARCHAR(100) NOT NULL, kind VARCHAR(30) NOT NULL,
 stock INT NOT NULL, capacity INT NOT NULL, safety_stock INT NOT NULL,
 x DOUBLE NOT NULL, y DOUBLE NOT NULL,
 CHECK(stock >= 0 AND stock <= capacity), CHECK(safety_stock >= 0 AND safety_stock <= capacity)
);
CREATE TABLE IF NOT EXISTS app_state (state_key VARCHAR(40) PRIMARY KEY, state_value VARCHAR(100) NOT NULL);
CREATE TABLE IF NOT EXISTS dispatch_tasks (
 id VARCHAR(60) PRIMARY KEY, source_id INT NOT NULL, destination_id INT NOT NULL,
 quantity INT NOT NULL, vehicle INT NOT NULL, status VARCHAR(20) NOT NULL,
 distance DOUBLE NOT NULL, duration INT NOT NULL, created_minute INT NOT NULL,
 started_minute INT, completed_minute INT,
 FOREIGN KEY(source_id) REFERENCES regions(id), FOREIGN KEY(destination_id) REFERENCES regions(id)
);
