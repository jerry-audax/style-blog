-- DB-AUDIT-01 cleanup, approved 2026-10-04.
--
-- These tables belonged to retired implementations and have no current
-- application mapper, repository, controller, or SQL read/write path:
--   * file_record   - retired local file storage
--   * sms_code_log  - retired SMS registration/login flow
--
-- Run only after taking a database backup. This migration intentionally does
-- not touch article content, users, comments, audit logs, or remote ImgBed
-- objects. It is idempotent so a partially applied cleanup can be retried.
BEGIN;

DROP TABLE IF EXISTS public.file_record;
DROP TABLE IF EXISTS public.sms_code_log;

COMMIT;
