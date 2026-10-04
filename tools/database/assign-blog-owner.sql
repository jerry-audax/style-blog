-- Run with psql -v owner_phone=... -v ON_ERROR_STOP=1 -f this file.
-- Does not create accounts, reset passwords, remove history, or modify other roles.
BEGIN;
SELECT pg_advisory_xact_lock(1930401);
CREATE
TEMP TABLE owner_assignment_guard (owner_id bigint NOT NULL, role_id bigint NOT NULL) ON COMMIT DROP;
INSERT INTO owner_assignment_guard
SELECT u.id, r.id
FROM sys_user u
         CROSS JOIN sys_role r
WHERE u.phone = :'owner_phone' AND u.deleted=0 AND u.status=1 AND u.password IS NOT NULL AND u.password <> ''
AND r.role_code='ADMIN' AND r.deleted=0 AND r.status=1;
DO
$$
BEGIN
    IF (SELECT count(*) FROM owner_assignment_guard) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one active existing owner with a password and active ADMIN role';
END IF;
END $$;
INSERT INTO sys_user_role(user_id, role_id)
SELECT owner_id, role_id
FROM owner_assignment_guard g
WHERE NOT EXISTS (SELECT 1 FROM sys_user_role ur WHERE ur.user_id = g.owner_id AND ur.role_id = g.role_id);
COMMIT;
