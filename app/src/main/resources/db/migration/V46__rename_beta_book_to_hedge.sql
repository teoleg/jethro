-- The auto-hedge lifecycle books its trades into a dedicated HEDGE book (jethro.hedge.book=HEDGE) so the
-- hedge's own position and P&L are unambiguous feedback (ADR-0039/0040), and risk limits for it are
-- already configured (jethro.risk.books.HEDGE.*). But the book MASTER never had a HEDGE row: hedge trades
-- pointed at a book absent from the tree, so the Book Structure / "Situation by book" view — which only
-- renders books present in the `book` table — silently dropped them (they were never shown, not skipped).
--
-- The BETA demo desk is unused; repurpose it as HEDGE. Rename in place: `book_id` is referenced only by
-- the self-FK `book.parent_id` (nothing parents BETA) and by plain, un-constrained `book_id` columns on
-- orders/fills/book_equity, so a rename + re-point leaves no orphans. Strategy routing never targets BETA
-- (equities→ALPHA, futures/FX/rates→MACRO), so no non-hedge flow lands here.

update book set book_id = 'HEDGE', name = 'Hedge Book' where book_id = 'BETA';

-- Re-point any historical rows booked to BETA (expected none — the desk was unused). Idempotent and safe:
-- these tables store book_id as a plain column with no foreign key to `book`.
update orders      set book_id = 'HEDGE' where book_id = 'BETA';
update fills       set book_id = 'HEDGE' where book_id = 'BETA';
update book_equity set book     = 'HEDGE' where book    = 'BETA';
