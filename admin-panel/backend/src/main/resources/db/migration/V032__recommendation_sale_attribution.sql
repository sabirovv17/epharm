-- V032 — атрибуция продаж к показанным рекомендациям (конверсия показ→продажа + время до продажи).
--
-- Задача 1   : отследить, был ли продан рекомендованный (продвигаемый) товар после показа.
-- Задача 1.2 : отследить, через какое время после показа рекомендации он был продан.
--
-- Заполняется на стороне backend при приёме завершённого чека (/api/posm/sales): если
-- рекомендованный товар оказался в чеке той же сессии — фиксируем факт и задержку. Корреляция
-- по session_id (одна сессия = один чек) надёжнее окна по времени. Это ЧИСТАЯ аналитика —
-- бонусную логику (pending_bonuses / outcome) не трогает.

ALTER TABLE recommendation_events
    ADD COLUMN displayed_at    TIMESTAMPTZ,   -- когда касса РЕАЛЬНО показала попап (пинг из outbox); fallback тайминга — shown_at
    ADD COLUMN sold_at         TIMESTAMPTZ,   -- printed_at чека, в котором продан рекомендованный товар (NULL — ещё не продан)
    ADD COLUMN sale_id         VARCHAR(64),   -- какой pos_sale закрыл рекомендацию (трассировка)
    ADD COLUMN seconds_to_sale INTEGER;       -- sold_at − COALESCE(displayed_at, shown_at), секунды (>=0)

-- Поиск неатрибутированных показов этого чека при приёме продажи.
CREATE INDEX idx_rec_events_session_unsold ON recommendation_events (session_id) WHERE sold_at IS NULL;

-- Окно аналитики по факту продажи (распределение времени до продажи).
CREATE INDEX idx_rec_events_sold_at ON recommendation_events (sold_at);
