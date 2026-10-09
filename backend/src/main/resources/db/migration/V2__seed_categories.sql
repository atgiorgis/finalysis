-- Seed spending categories (DAT-02).
-- Categories describe types of spending and income, so the same set applies to every account.
-- "Transfer" covers all movement between the user's own accounts, including paying off a
-- credit card; card payments can be told apart later via transfer_link and the account type.

INSERT INTO category (name, kind) VALUES
    -- Categories used in the sample-data answer key.
    ('Dining',               'EXPENSE'),
    ('Groceries',            'EXPENSE'),
    ('Health',               'EXPENSE'),
    ('Housing',              'EXPENSE'),
    ('Shopping',             'EXPENSE'),
    ('Subscriptions',        'EXPENSE'),
    ('Transportation',       'EXPENSE'),
    ('Utilities',            'EXPENSE'),
    ('Income',               'INCOME'),
    ('Interest',             'INCOME'),
    ('Transfer',             'TRANSFER'),
    -- General-purpose categories beyond the answer key.
    ('Bank Fees',            'EXPENSE'),
    ('Cash & ATM',           'EXPENSE'),
    ('Mortgage',             'EXPENSE'),
    ('HOA & Condo Fees',     'EXPENSE'),
    ('Tolls',                'EXPENSE'),
    ('Person-to-Person Out', 'EXPENSE'),
    ('Person-to-Person In',  'INCOME'),
    ('Refunds & Rebates',    'INCOME');
