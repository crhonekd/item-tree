-- =============================================================================
-- ITEMTREE seed data — Phase A dev profile.
--
-- Hand-allocated ids 1..99; sequence starts at 100000 so live inserts won't
-- collide. All LASTUPDATE values are UTC. Tree shape covers:
--   * root + first-level skeleton
--   * Users / home folders (testuser1, testuser2, deepuser)
--   * depth-7 chain under deepuser (ancestor-walk testing)
--   * one row of every §10 type
--   * one JSON-null + XML-not-null row (backfill candidate)
--   * one mixed-children folder (subfolders + leaves)
--
-- DELETE first so the script is idempotent: multiple ApplicationContexts that
-- share the same named in-memory H2 database can each run this script without
-- getting duplicate-key errors.
-- =============================================================================
DELETE FROM ITEMTREE;

-- ── 1. Root ──────────────────────────────────────────────────────────────────
INSERT INTO ITEMTREE (ITEMTREEID, PARENTID, NAME, TYPE, XML, LASTUPDATEUSER, LASTUPDATE, JSON) VALUES
  (1, 0, 'root', 'Folder', NULL, 'system', TIMESTAMP '2026-05-01 10:00:00', NULL);

-- ── 2. First-level folders ───────────────────────────────────────────────────
INSERT INTO ITEMTREE (ITEMTREEID, PARENTID, NAME, TYPE, XML, LASTUPDATEUSER, LASTUPDATE, JSON) VALUES
  (2, 1, 'Users',     'Folder', NULL, 'system', TIMESTAMP '2026-05-01 10:00:00', NULL),
  (3, 1, 'Reports',   'Folder', NULL, 'system', TIMESTAMP '2026-05-01 10:00:00', NULL),
  (4, 1, 'Filters',   'Folder', NULL, 'system', TIMESTAMP '2026-05-01 10:00:00', NULL),
  (5, 1, 'Shortcuts', 'Folder', NULL, 'system', TIMESTAMP '2026-05-01 10:00:00', NULL),
  (6, 1, 'Datasets',  'Folder', NULL, 'system', TIMESTAMP '2026-05-01 10:00:00', NULL);

-- ── 3. Home folders under Users ──────────────────────────────────────────────
INSERT INTO ITEMTREE (ITEMTREEID, PARENTID, NAME, TYPE, XML, LASTUPDATEUSER, LASTUPDATE, JSON) VALUES
  (10, 2, 'testuser1', 'Folder', NULL, 'system', TIMESTAMP '2026-05-01 10:00:00', NULL),
  (11, 2, 'testuser2', 'Folder', NULL, 'system', TIMESTAMP '2026-05-01 10:00:00', NULL),
  (12, 2, 'deepuser',  'Folder', NULL, 'system', TIMESTAMP '2026-05-01 10:00:00', NULL);

-- ── 4. Depth-7 chain under deepuser: deepuser/L2/L3/L4/L5/L6/leafItem ────────
INSERT INTO ITEMTREE (ITEMTREEID, PARENTID, NAME, TYPE, XML, LASTUPDATEUSER, LASTUPDATE, JSON) VALUES
  (20, 12, 'L2', 'Folder', NULL, 'system', TIMESTAMP '2026-05-01 10:00:00', NULL),
  (21, 20, 'L3', 'Folder', NULL, 'system', TIMESTAMP '2026-05-01 10:00:00', NULL),
  (22, 21, 'L4', 'Folder', NULL, 'system', TIMESTAMP '2026-05-01 10:00:00', NULL),
  (23, 22, 'L5', 'Folder', NULL, 'system', TIMESTAMP '2026-05-01 10:00:00', NULL),
  (24, 23, 'L6', 'Folder', NULL, 'system', TIMESTAMP '2026-05-01 10:00:00', NULL),
  (25, 24, 'leafItem', 'Report',
        '<report><name>leaf</name><n>1</n></report>',
        'system', TIMESTAMP '2026-05-01 10:00:00',
        '{"name":"leaf","n":1}');

-- ── 5a. types-without-data — under Shortcuts (XML & JSON both NULL) ──────────
INSERT INTO ITEMTREE (ITEMTREEID, PARENTID, NAME, TYPE, XML, LASTUPDATEUSER, LASTUPDATE, JSON) VALUES
  (30, 5, 'SC1', 'Shortcut',               NULL, 'system', TIMESTAMP '2026-05-01 10:00:00', NULL),
  (31, 5, 'SC2', 'Shortcut.Report',        NULL, 'system', TIMESTAMP '2026-05-01 10:00:00', NULL),
  (32, 5, 'SC3', 'Shortcut.Filter',        NULL, 'system', TIMESTAMP '2026-05-01 10:00:00', NULL),
  (33, 5, 'SC4', 'Shortcut.Filter.Nested', NULL, 'system', TIMESTAMP '2026-05-01 10:00:00', NULL);

-- ── 5b. types-also-persisted-as-xml-on-write — JSON + XML both populated ─────
INSERT INTO ITEMTREE (ITEMTREEID, PARENTID, NAME, TYPE, XML, LASTUPDATEUSER, LASTUPDATE, JSON) VALUES
  (40, 6, 'DrillDownSet1',  'DrillDown.Set',
        '<drilldown><name>ds1</name><n>1</n></drilldown>',
        'system', TIMESTAMP '2026-05-01 10:00:00',
        '{"name":"ds1","n":1}'),
  (41, 3, 'Report1',        'Report',
        '<report><name>r1</name><n>1</n></report>',
        'system', TIMESTAMP '2026-05-01 10:00:00',
        '{"name":"r1","n":1}'),
  (42, 4, 'Filter1',        'Filter',
        '<filter><name>f1</name><n>1</n></filter>',
        'system', TIMESTAMP '2026-05-01 10:00:00',
        '{"name":"f1","n":1}'),
  (43, 6, 'DetailsCC1',     'Details.Column.Collection',
        '<details><name>dcc1</name><n>1</n></details>',
        'system', TIMESTAMP '2026-05-01 10:00:00',
        '{"name":"dcc1","n":1}'),
  (44, 6, 'NumericBC1',     'Numeric.Bucket.Collection',
        '<numeric><name>nbc1</name><n>1</n></numeric>',
        'system', TIMESTAMP '2026-05-01 10:00:00',
        '{"name":"nbc1","n":1}'),
  (45, 6, 'DiscreteBC1',    'Discrete.Bucket.Collection',
        '<discrete><name>dbc1</name><n>1</n></discrete>',
        'system', TIMESTAMP '2026-05-01 10:00:00',
        '{"name":"dbc1","n":1}'),
  (46, 6, 'BucketC1',       'Bucket.Collection',
        '<bucket><name>bc1</name><n>1</n></bucket>',
        'system', TIMESTAMP '2026-05-01 10:00:00',
        '{"name":"bc1","n":1}');

-- ── 5c. JSON-only new-format types — XML NULL, JSON populated ────────────────
INSERT INTO ITEMTREE (ITEMTREEID, PARENTID, NAME, TYPE, XML, LASTUPDATEUSER, LASTUPDATE, JSON) VALUES
  (50, 3, 'View1',        'View',        NULL, 'system', TIMESTAMP '2026-05-01 10:00:00', '{"name":"v1","n":1}'),
  (51, 3, 'UDFContext1',  'UDF.Context', NULL, 'system', TIMESTAMP '2026-05-01 10:00:00', '{"name":"u1","n":1}'),
  (52, 3, 'Eval1',        'Eval',        NULL, 'system', TIMESTAMP '2026-05-01 10:00:00', '{"name":"e1","n":1}');

-- ── 6. Backfill candidate — JSON NULL, XML populated ─────────────────────────
INSERT INTO ITEMTREE (ITEMTREEID, PARENTID, NAME, TYPE, XML, LASTUPDATEUSER, LASTUPDATE, JSON) VALUES
  (60, 3, 'BackfillReport', 'Report',
        '<report><name>backfill-me</name><n>42</n></report>',
        'system', TIMESTAMP '2026-05-01 10:00:00', NULL);

-- ── 7. Mixed-children folder — subfolders + leaves under Reports ─────────────
INSERT INTO ITEMTREE (ITEMTREEID, PARENTID, NAME, TYPE, XML, LASTUPDATEUSER, LASTUPDATE, JSON) VALUES
  (70, 3, 'MixedFolder',    'Folder', NULL, 'system', TIMESTAMP '2026-05-01 10:00:00', NULL),
  (71, 70, 'MixedSubfolder', 'Folder', NULL, 'system', TIMESTAMP '2026-05-01 10:00:00', NULL),
  (72, 70, 'MixedLeaf',     'View',   NULL, 'system', TIMESTAMP '2026-05-01 10:00:00', '{"name":"ml","n":1}');

COMMIT;
