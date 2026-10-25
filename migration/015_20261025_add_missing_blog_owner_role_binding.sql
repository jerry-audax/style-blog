-- 2026-10-25: add the role required by the single-owner API to legacy sites.
-- Run with psql -v owner_phone=... -v ON_ERROR_STOP=1 after backup and approval.
-- Preserve ADMIN/USER, their bindings, passwords and permission tables for rollback.
BEGIN;
SELECT pg_advisory_xact_lock(1930401);

CREATE TEMP TABLE blog_owner_upgrade_account_guard (
    owner_id BIGINT NOT NULL CHECK (owner_id > 0)
) ON COMMIT DROP;
INSERT INTO blog_owner_upgrade_account_guard(owner_id)
VALUES ((SELECT id FROM sys_user
         WHERE phone = :'owner_phone' AND status = 1 AND deleted = 0
           AND password IS NOT NULL AND btrim(password) <> ''));

INSERT INTO sys_role(role_code, role_name, status, remark, deleted)
SELECT 'BLOG_OWNER', '博主', 1, 'Single blog owner; legacy roles retained for rollback', 0
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE lower(role_code) = 'blog_owner');

CREATE TEMP TABLE blog_owner_upgrade_role_guard (
    role_id BIGINT NOT NULL CHECK (role_id > 0)
) ON COMMIT DROP;
-- Existing disabled/deleted roles are rejected, rather than silently reactivated.
INSERT INTO blog_owner_upgrade_role_guard(role_id)
VALUES ((SELECT id FROM sys_role
         WHERE role_code = 'BLOG_OWNER' AND status = 1 AND deleted = 0));

INSERT INTO sys_user_role(user_id, role_id)
SELECT account.owner_id, role.role_id
FROM blog_owner_upgrade_account_guard account
CROSS JOIN blog_owner_upgrade_role_guard role
WHERE NOT EXISTS (SELECT 1 FROM sys_user_role existing
                  WHERE existing.user_id = account.owner_id AND existing.role_id = role.role_id);
COMMIT;
