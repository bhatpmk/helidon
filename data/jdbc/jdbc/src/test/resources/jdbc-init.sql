-- Semicolons inside strings and comments are not statement boundaries: ;
CREATE TABLE INIT_CONTACT (
    ID BIGINT PRIMARY KEY,
    NAME VARCHAR(80)
);
INSERT INTO INIT_CONTACT (ID, NAME) VALUES (1, 'Ada; Lovelace');
INSERT INTO INIT_CONTACT (ID, NAME) VALUES (2, 'Grace Hopper');
