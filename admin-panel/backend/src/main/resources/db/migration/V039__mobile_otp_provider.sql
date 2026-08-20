-- OTP может проверяться локально (dev/p1sms) или внешним Daribar gateway.
-- Старые строки помечаем local: после переключения провайдера пользователь должен запросить
-- новый код, а фиксированный pilot-код не сможет пройти через внешний verifier.
ALTER TABLE mobile_otps
    ADD COLUMN verification_provider VARCHAR(32) NOT NULL DEFAULT 'local';
