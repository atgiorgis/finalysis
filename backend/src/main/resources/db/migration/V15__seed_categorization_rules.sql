-- Seeded categorization rules (CAT-01, Phase 4 step 2; docs/plans/phase-4.md §3.3).
--
-- Policy: national brands, payment services and generic English category words only. Never a name
-- from the sample data (Blue Bean Cafe, Parkside, Brightway, Skywave...), so the CAT-05 eval isn't
-- rigged. Tuned against the answer key, the merchant edge-case fixture and the dev set only, never
-- the frozen set (SeedRulesTest checks that no seed matches one of its names).
--
-- Direction: a seed for an expense category matches money out only, one for an income category money
-- in only, so a refund at a store or a card's interest charge is never taken for spending or income.
-- Transfer seeds match either way.
--
-- Priority bands (user rules outrank all of these whatever their priority, §3.2):
--   100  person-to-person services: the service decides, whatever the description says
--    50  structural description phrases: transfers, card payments, payroll, interest, bank fees,
--        cash, refunds, rent, recurring payments
--    20  national brands (30 where a brand needs to beat a shorter one: UBER EATS over UBER)
--     0  generic category words in the merchant (CAFE, PHARMACY, ELECTRIC...)
--
-- Text patterns match whole words (SHELL never matches SHELLFISH). Regexes are matched
-- case-insensitively on the normalized description (upper case, single spaces).
-- Seeds can be deactivated through the API, never edited or deleted.

CREATE TEMPORARY TABLE seed_rule (
    field      TEXT NOT NULL,
    match_type TEXT NOT NULL,
    pattern    TEXT NOT NULL,
    direction  TEXT NOT NULL,
    priority   INT  NOT NULL,
    category   TEXT NOT NULL
) ON COMMIT DROP;

INSERT INTO seed_rule (field, match_type, pattern, direction, priority, category) VALUES
    -- Person-to-person services --------------------------------------------------------------
    ('MERCHANT',    'EXACT',    'ZELLE',    'OUT', 100, 'Person-to-Person Out'),
    ('MERCHANT',    'EXACT',    'ZELLE',    'IN',  100, 'Person-to-Person In'),
    ('MERCHANT',    'EXACT',    'VENMO',    'OUT', 100, 'Person-to-Person Out'),
    ('MERCHANT',    'EXACT',    'VENMO',    'IN',  100, 'Person-to-Person In'),
    ('MERCHANT',    'EXACT',    'CASH APP', 'OUT', 100, 'Person-to-Person Out'),
    ('MERCHANT',    'EXACT',    'CASH APP', 'IN',  100, 'Person-to-Person In'),

    -- Structural description phrases ---------------------------------------------------------
    ('DESCRIPTION', 'REGEX', '\bONLINE TRANSFER (TO|FROM)\b',                                   'ANY', 50, 'Transfer'),
    ('DESCRIPTION', 'REGEX', '\bTRANSFER (TO|FROM) (CHK|CHECKING|SAV|SAVINGS|MONEY MARKET)\b',  'ANY', 50, 'Transfer'),
    ('DESCRIPTION', 'REGEX', '\bPAYMENT\b.*\bTHANK YOU\b',                                      'ANY', 50, 'Transfer'),
    ('DESCRIPTION', 'REGEX', '\b(CARD|VISA|MASTERCARD|AMEX|DISCOVER) (ONLINE |AUTO ?|E-?)?(PAYMENT|PMT|PAY)\b',
                                                                                                'ANY', 50, 'Transfer'),
    ('DESCRIPTION', 'REGEX', '\bPAYROLL\b',                                                     'IN',  50, 'Income'),
    ('DESCRIPTION', 'REGEX', '\bDIR(ECT)? DEP(OSIT)?\b',                                        'IN',  50, 'Income'),
    ('DESCRIPTION', 'REGEX', '\bINTEREST (PAYMENT|PAID|EARNED)\b',                              'IN',  50, 'Interest'),
    ('DESCRIPTION', 'REGEX', '\b(MONTHLY|SERVICE|MAINTENANCE|OVERDRAFT|NSF|FOREIGN TRANSACTION|ATM|WIRE) FEE\b',
                                                                                                'OUT', 50, 'Bank Fees'),
    ('DESCRIPTION', 'REGEX', '\b(ATM|CASH) WITHDRAWAL\b',                                       'OUT', 50, 'Cash & ATM'),
    ('DESCRIPTION', 'REGEX', '\b(REFUND|RETURN|CREDIT VOUCHER|CASH ?BACK|REWARDS?|REBATE)\b',  'IN',  50, 'Refunds & Rebates'),
    ('DESCRIPTION', 'REGEX', '\bRENT\b(?! A CAR)',                                              'OUT', 50, 'Housing'),
    ('DESCRIPTION', 'REGEX', '\bRECURRING (PMT|PAYMENT|CHARGE)\b',                              'OUT', 50, 'Subscriptions'),

    -- National brands -------------------------------------------------------------------------
    ('MERCHANT',    'CONTAINS', 'AMZN MKTP',   'OUT', 20, 'Shopping'),
    ('MERCHANT',    'CONTAINS', 'AMAZON',      'OUT', 20, 'Shopping'),
    ('MERCHANT',    'CONTAINS', 'TARGET',      'OUT', 20, 'Shopping'),
    ('MERCHANT',    'CONTAINS', 'BEST BUY',    'OUT', 20, 'Shopping'),
    ('MERCHANT',    'CONTAINS', 'WALMART',     'OUT', 20, 'Shopping'),
    ('MERCHANT',    'CONTAINS', 'NETFLIX',     'OUT', 20, 'Subscriptions'),
    ('MERCHANT',    'CONTAINS', 'SPOTIFY',     'OUT', 20, 'Subscriptions'),
    ('MERCHANT',    'CONTAINS', 'HULU',        'OUT', 20, 'Subscriptions'),
    ('MERCHANT',    'CONTAINS', 'GOOGLE ONE',  'OUT', 20, 'Subscriptions'),
    ('MERCHANT',    'CONTAINS', 'UBER EATS',   'OUT', 30, 'Dining'),
    ('MERCHANT',    'CONTAINS', 'CHIPOTLE',    'OUT', 20, 'Dining'),
    ('MERCHANT',    'CONTAINS', 'STARBUCKS',   'OUT', 20, 'Dining'),
    ('MERCHANT',    'CONTAINS', 'DOORDASH',    'OUT', 20, 'Dining'),
    ('MERCHANT',    'CONTAINS', 'UBER',        'OUT', 20, 'Transportation'),
    ('MERCHANT',    'CONTAINS', 'LYFT',        'OUT', 20, 'Transportation'),
    ('MERCHANT',    'CONTAINS', 'SHELL',       'OUT', 20, 'Transportation'),
    ('MERCHANT',    'CONTAINS', 'EXXON',       'OUT', 20, 'Transportation'),
    ('MERCHANT',    'CONTAINS', 'CHEVRON',     'OUT', 20, 'Transportation'),
    ('MERCHANT',    'CONTAINS', 'SAFEWAY',     'OUT', 20, 'Groceries'),
    ('MERCHANT',    'CONTAINS', 'GIANT FOOD',  'OUT', 20, 'Groceries'),
    ('MERCHANT',    'CONTAINS', 'TRADER JOE',  'OUT', 20, 'Groceries'),
    ('MERCHANT',    'CONTAINS', 'WHOLE FOODS', 'OUT', 20, 'Groceries'),
    ('MERCHANT',    'CONTAINS', 'KROGER',      'OUT', 20, 'Groceries'),
    ('MERCHANT',    'CONTAINS', 'CVS',         'OUT', 20, 'Health'),
    ('MERCHANT',    'CONTAINS', 'WALGREENS',   'OUT', 20, 'Health'),

    -- Generic category words --------------------------------------------------------------------
    ('MERCHANT',    'CONTAINS', 'CAFE',             'OUT', 0, 'Dining'),
    ('MERCHANT',    'CONTAINS', 'COFFEE',           'OUT', 0, 'Dining'),
    ('MERCHANT',    'CONTAINS', 'RESTAURANT',       'OUT', 0, 'Dining'),
    ('MERCHANT',    'CONTAINS', 'PIZZA',            'OUT', 0, 'Dining'),
    ('MERCHANT',    'CONTAINS', 'PIZZERIA',         'OUT', 0, 'Dining'),
    ('MERCHANT',    'CONTAINS', 'DELI',             'OUT', 0, 'Dining'),
    ('MERCHANT',    'CONTAINS', 'BAKERY',           'OUT', 0, 'Dining'),
    ('MERCHANT',    'CONTAINS', 'BAGEL',            'OUT', 0, 'Dining'),
    ('MERCHANT',    'CONTAINS', 'BAGELS',           'OUT', 0, 'Dining'),
    ('MERCHANT',    'CONTAINS', 'GRILL',            'OUT', 0, 'Dining'),
    ('MERCHANT',    'CONTAINS', 'TAVERN',           'OUT', 0, 'Dining'),
    ('MERCHANT',    'CONTAINS', 'DINER',            'OUT', 0, 'Dining'),
    ('MERCHANT',    'CONTAINS', 'BISTRO',           'OUT', 0, 'Dining'),
    ('MERCHANT',    'CONTAINS', 'RAMEN',            'OUT', 0, 'Dining'),
    ('MERCHANT',    'CONTAINS', 'SUSHI',            'OUT', 0, 'Dining'),
    ('MERCHANT',    'CONTAINS', 'TAQUERIA',         'OUT', 0, 'Dining'),
    ('MERCHANT',    'CONTAINS', 'THAI',             'OUT', 0, 'Dining'),
    ('MERCHANT',    'CONTAINS', 'GROCERY',          'OUT', 0, 'Groceries'),
    ('MERCHANT',    'CONTAINS', 'GROCERS',          'OUT', 0, 'Groceries'),
    ('MERCHANT',    'CONTAINS', 'SUPERMARKET',      'OUT', 0, 'Groceries'),
    ('MERCHANT',    'CONTAINS', 'MARKET',           'OUT', 0, 'Groceries'),
    ('MERCHANT',    'CONTAINS', 'FOODS',            'OUT', 0, 'Groceries'),
    ('MERCHANT',    'CONTAINS', 'FRESH MART',       'OUT', 0, 'Groceries'),
    ('MERCHANT',    'CONTAINS', 'ORGANICS',         'OUT', 0, 'Groceries'),
    ('MERCHANT',    'CONTAINS', 'PHARMACY',         'OUT', 0, 'Health'),
    ('MERCHANT',    'CONTAINS', 'DENTAL',           'OUT', 0, 'Health'),
    ('MERCHANT',    'CONTAINS', 'MEDICINE',         'OUT', 0, 'Health'),
    ('MERCHANT',    'CONTAINS', 'MEDICAL',          'OUT', 0, 'Health'),
    ('MERCHANT',    'CONTAINS', 'CLINIC',           'OUT', 0, 'Health'),
    ('MERCHANT',    'CONTAINS', 'OPTOMETRY',        'OUT', 0, 'Health'),
    ('MERCHANT',    'CONTAINS', 'PHYSICAL THERAPY', 'OUT', 0, 'Health'),
    ('MERCHANT',    'CONTAINS', 'HOSPITAL',         'OUT', 0, 'Health'),
    ('MERCHANT',    'CONTAINS', 'URGENT CARE',      'OUT', 0, 'Health'),
    ('MERCHANT',    'CONTAINS', 'OUTFITTERS',       'OUT', 0, 'Shopping'),
    ('MERCHANT',    'CONTAINS', 'BOOKS',            'OUT', 0, 'Shopping'),
    ('MERCHANT',    'CONTAINS', 'HARDWARE',         'OUT', 0, 'Shopping'),
    ('MERCHANT',    'CONTAINS', 'HOME GOODS',       'OUT', 0, 'Shopping'),
    ('MERCHANT',    'CONTAINS', 'ELECTRONICS',      'OUT', 0, 'Shopping'),
    ('MERCHANT',    'CONTAINS', 'TOYS',             'OUT', 0, 'Shopping'),
    ('MERCHANT',    'CONTAINS', 'APPAREL',          'OUT', 0, 'Shopping'),
    ('MERCHANT',    'CONTAINS', 'FUEL',             'OUT', 0, 'Transportation'),
    ('MERCHANT',    'CONTAINS', 'PARKING',          'OUT', 0, 'Transportation'),
    ('MERCHANT',    'CONTAINS', 'AUTO REPAIR',      'OUT', 0, 'Transportation'),
    ('MERCHANT',    'CONTAINS', 'TRANSIT',          'OUT', 0, 'Transportation'),
    ('MERCHANT',    'CONTAINS', 'ELECTRIC',         'OUT', 0, 'Utilities'),
    ('MERCHANT',    'CONTAINS', 'POWER CO',         'OUT', 0, 'Utilities'),
    ('MERCHANT',    'CONTAINS', 'WATER UTIL',       'OUT', 0, 'Utilities'),
    ('MERCHANT',    'CONTAINS', 'WIRELESS',         'OUT', 0, 'Utilities'),
    ('MERCHANT',    'CONTAINS', 'INTERNET',         'OUT', 0, 'Utilities'),
    ('MERCHANT',    'CONTAINS', 'MORTGAGE',         'OUT', 0, 'Mortgage'),
    ('MERCHANT',    'CONTAINS', 'HOA',              'OUT', 0, 'HOA & Condo Fees'),
    ('MERCHANT',    'CONTAINS', 'TOLL',             'OUT', 0, 'Tolls');

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM seed_rule s LEFT JOIN category c ON c.name = s.category WHERE c.id IS NULL) THEN
        RAISE EXCEPTION 'A seed rule names a category that does not exist';
    END IF;
END;
$$;

INSERT INTO categorization_rule (field, match_type, pattern, direction, priority, source, category_id)
SELECT s.field, s.match_type, s.pattern, s.direction, s.priority, 'SEED', c.id
FROM seed_rule s
JOIN category c ON c.name = s.category;
