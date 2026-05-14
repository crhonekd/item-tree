-- ITEMTREE — production schema mirror.
-- H2 in MODE=Oracle accepts these types and the recursive CTE / sequence syntax.
-- IF NOT EXISTS guards allow multiple ApplicationContexts to share the same
-- named in-memory H2 database (e.g. ItemTreeApplicationTests + ApiContractTest)
-- without failing on the second schema initialisation.
CREATE TABLE IF NOT EXISTS ITEMTREE (
    ITEMTREEID     NUMBER(10) PRIMARY KEY,
    PARENTID       NUMBER(10) NOT NULL,
    NAME           VARCHAR2(70) NOT NULL,
    TYPE           VARCHAR2(30) NOT NULL,
    XML            CLOB,
    LASTUPDATEUSER VARCHAR2(20),
    LASTUPDATE     DATE,
    JSON           CLOB
);

CREATE INDEX IF NOT EXISTS IDX_ITEMTREE_PARENTID  ON ITEMTREE(PARENTID);
CREATE INDEX IF NOT EXISTS IDX_ITEMTREE_LASTUPDATE ON ITEMTREE(LASTUPDATE);

CREATE SEQUENCE IF NOT EXISTS ITEMTREE_ID_SQN START WITH 100000 INCREMENT BY 1;
