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
 started_minute INT, completed_minute INT, reason VARCHAR(300),
 FOREIGN KEY(source_id) REFERENCES regions(id), FOREIGN KEY(destination_id) REFERENCES regions(id)
);
ALTER TABLE dispatch_tasks ADD COLUMN IF NOT EXISTS reason VARCHAR(300);
CREATE TABLE IF NOT EXISTS road_matrix (
 origin_id INT NOT NULL, destination_id INT NOT NULL,
 distance_km DOUBLE NOT NULL, duration_min INT NOT NULL,
 PRIMARY KEY(origin_id, destination_id)
);
ALTER TABLE dispatch_tasks ADD COLUMN IF NOT EXISTS trip_id VARCHAR(40);
ALTER TABLE dispatch_tasks ADD COLUMN IF NOT EXISTS sequence_no INT DEFAULT 1;

CREATE TABLE IF NOT EXISTS dispatch_trips (
 id VARCHAR(40) PRIMARY KEY, vehicle INT NOT NULL, status VARCHAR(20) NOT NULL,
 start_minute INT, created_minute INT NOT NULL
);
ALTER TABLE dispatch_tasks ADD COLUMN IF NOT EXISTS pickup_seq INT;
ALTER TABLE dispatch_tasks ADD COLUMN IF NOT EXISTS delivery_seq INT;
