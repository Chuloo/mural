-- A declined request changes no entitlement, but must not stall notification-history recovery.
ALTER TABLE apple_purchase_notifications DROP CONSTRAINT apple_purchase_notifications_notification_type_check;
ALTER TABLE apple_purchase_notifications ADD CONSTRAINT apple_purchase_notifications_notification_type_check
  CHECK(notification_type IN ('ONE_TIME_CHARGE','REFUND','REFUND_REVERSED','REFUND_DECLINED','CONSUMPTION_REQUEST'));
