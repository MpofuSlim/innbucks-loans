-- The SuperApp inbox (FR-SGL-019, FR-SGL-020): a staff member reads their in-app notifications, so each one records
-- when they first read it. Null until then; a notification read again keeps its first time. The outbound columns are
-- the dispatcher's and are never written here, nor this one by the dispatcher.
ALTER TABLE staff_notifications ADD COLUMN read_at TIMESTAMP(6);

-- The unread count, per member.
CREATE INDEX ix_staff_notifications_unread ON staff_notifications (staff_member_id) WHERE read_at IS NULL;
