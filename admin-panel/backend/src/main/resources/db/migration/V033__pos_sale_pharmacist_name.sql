-- Имя продавца в момент продажи. Нужен как снимок из Standard-N, когда его USER_ID ещё не
-- сопоставлен с внутренним pharmacist.id. Nullable сохраняет совместимость со старыми POSM.
ALTER TABLE pos_sales
    ADD COLUMN pharmacist_name VARCHAR(255);
