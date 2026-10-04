-- ---------------------------------------------------------------------------------------------
-- Legacy OMS schema (Oracle dialect). Runs unchanged on H2 with MODE=Oracle.
-- In production this lives in the OMSPRD database, schema OMS_OWNER, tablespace OMS_DATA.
-- ---------------------------------------------------------------------------------------------

-- Re-runnable (Oracle 23ai and H2 both accept IF EXISTS): several Spring test contexts share the in-memory DB.
DROP TABLE IF EXISTS ORD_LINE;
DROP TABLE IF EXISTS ORD_HDR;
DROP SEQUENCE IF EXISTS ORD_HDR_SEQ;

CREATE SEQUENCE ORD_HDR_SEQ START WITH 1000 INCREMENT BY 1;

CREATE TABLE ORD_HDR (
    ORD_ID       NUMBER(12)      NOT NULL,
    ORD_NBR      VARCHAR2(20)    NOT NULL,
    ORD_TYPE     VARCHAR2(1)     NOT NULL,          -- R/T/C/X/E
    STORE_NBR    VARCHAR2(4)     NOT NULL,
    CUST_NBR     VARCHAR2(20),
    ORD_DATE     DATE            NOT NULL,
    EVENT_DATE   DATE,                              -- rental event date (X orders)
    STATUS       VARCHAR2(15)    DEFAULT 'NEW' NOT NULL,
    TOTAL_AMT    NUMBER(12,2)    DEFAULT 0 NOT NULL,
    CREATED_TS   TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    UPDATED_TS   TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT PK_ORD_HDR PRIMARY KEY (ORD_ID),
    CONSTRAINT UK_ORD_HDR_NBR UNIQUE (ORD_NBR),
    CONSTRAINT CK_ORD_HDR_TYPE CHECK (ORD_TYPE IN ('R', 'T', 'C', 'X', 'E')),
    CONSTRAINT CK_ORD_HDR_STATUS CHECK (STATUS IN ('NEW', 'RELEASED', 'IN_ALTERATION', 'READY', 'SHIPPED', 'COMPLETE', 'CANCELLED'))
);

CREATE TABLE ORD_LINE (
    ORD_ID              NUMBER(12)      NOT NULL,
    LINE_NBR            NUMBER(4)       NOT NULL,
    SKU                 VARCHAR2(40)    NOT NULL,
    QTY                 NUMBER(6)       NOT NULL,
    PRICE               NUMBER(12,2)    NOT NULL,
    FULFILL_TYPE        VARCHAR2(1)     NOT NULL,   -- P/S/A
    ALT_TYPE            VARCHAR2(20),
    ALT_MEASUREMENT_IN  NUMBER(6,2),
    TAILOR_SHOP_NBR     VARCHAR2(20),
    CONSTRAINT PK_ORD_LINE PRIMARY KEY (ORD_ID, LINE_NBR),
    CONSTRAINT FK_ORD_LINE_HDR FOREIGN KEY (ORD_ID) REFERENCES ORD_HDR (ORD_ID),
    CONSTRAINT CK_ORD_LINE_FT CHECK (FULFILL_TYPE IN ('P', 'S', 'A'))
);

CREATE INDEX IX_ORD_HDR_DATE   ON ORD_HDR (ORD_DATE);
CREATE INDEX IX_ORD_HDR_STORE  ON ORD_HDR (STORE_NBR, ORD_DATE);
CREATE INDEX IX_ORD_LINE_SKU   ON ORD_LINE (SKU);

COMMENT ON TABLE ORD_HDR IS 'Order header - one row per POS / e-com order received from TIBCO BW';
COMMENT ON TABLE ORD_LINE IS 'Order lines incl. alteration work-order attributes';
