-- AI sleeve (ADR-0022): a dedicated book for the LLM hypothesis layer's bounded-autonomy
-- trades, separate from the momentum strategy's books (ALPHA/MACRO). Keeping the two engines
-- in different books stops them fighting over the same position — the algo was flattening
-- positions the AI had just opened (churn + cost bleed) because both traded the same book.
-- Separate sleeves also make each engine's P&L attributable on its own.

insert into book (book_id, name, base_currency, parent_id) values
    ('AI', 'AI Sleeve', 'USD', 'FIRM');
