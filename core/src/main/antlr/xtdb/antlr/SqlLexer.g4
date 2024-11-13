lexer grammar SqlLexer;

options {
  language = Java;
  caseInsensitive = true;
}

@members {
    String dollarTag = null;
}

BLOCK_COMMENT : '/*' ( BLOCK_COMMENT | . )*? '*/'  -> skip ;
LINE_COMMENT : '--' ~[\r\n]* -> skip ;
WHITESPACE : [ \n\r\t]+ -> skip ;

fragment DIGIT : [0-9] ;
fragment LETTER : [a-z] ;
fragment HEX_DIGIT : [0-9a-f] ;
fragment SIGN : '+' | '-' ;

fragment EXPONENT : 'E' SIGN? DIGIT+ ;
UNSIGNED_FLOAT : DIGIT+ ('.' DIGIT+ EXPONENT? | EXPONENT) ;
UNSIGNED_INTEGER : DIGIT+ ;

CHARACTER_STRING : '\'' ('\'\'' | ~('\''|'\n'))* '\'' ;
C_ESCAPES_STRING : 'E\''
  ( '\\' [0-7] [0-7]? [0-7]?
  | '\\x' HEX_DIGIT HEX_DIGIT
  | '\\u' HEX_DIGIT HEX_DIGIT HEX_DIGIT HEX_DIGIT
  | '\\U' HEX_DIGIT HEX_DIGIT HEX_DIGIT HEX_DIGIT HEX_DIGIT HEX_DIGIT HEX_DIGIT HEX_DIGIT
  | '\\' ('b' | 'f' | 'n' | 'r' | 't' | '\\' | '\'' | '"')
  | '\'\''
  | ~('\\' | '\'')
  )*
  '\'' ;

BINARY_STRING : 'X(\'' HEX_DIGIT* '\')' ;

POSTGRES_PARAMETER_SPECIFICATION : '$' DIGIT+ ;

COMMA : ',' ;
DOT : '.' ;
SEMI : ';' ;
COLON : ':' ;
QUESTION : '?' ;
LPAREN : '(' ;
RPAREN : ')' ;
LBRACK : '[' ;
RBRACK : ']' ;
LBRACE : '{' ;
RBRACE : '}' ;
CONCAT : '||' ;
TILDE : '~' ;
AMPERSAND : '&' ;

PLUS : '+' ;
MINUS : '-' ;
ASTERISK : '*' ;
SOLIDUS : '/' ;

BITWISE_OR : '|' ;
BITWISE_XOR : '#' ;
BITWISE_SHIFT_LEFT : '<<' ;
BITWISE_SHIFT_RIGHT : '>>' ;

EQUAL : '=' ;
NOT_EQUAL : '!=' ;
LT_GT : '<>' ;
LT : '<' ;
GT : '>' ;
LE : '<=' ;
GE : '>=' ;

PG_CAST : '::' ;
PG_REGEX_I : '~*' ;
PG_NOT_REGEX : '!~' ;
PG_NOT_REGEX_I : '!~*' ;

ABS : 'ABS' ;
ACOS : 'ACOS' ;
AGE : 'AGE' ;
ALL: 'ALL' ;
AND : 'AND' ;
ANY : 'ANY' ;
ARRAY : 'ARRAY' ;
ARRAY_AGG : 'ARRAY_AGG' ;
ARRAY_UPPER : 'ARRAY_UPPER' ;
ARROW_TABLE : 'ARROW_TABLE' ;
AS : 'AS' ;
ASC : 'ASC' ;
ASIN : 'ASIN' ;
ASSERT : 'ASSERT' ;
ASYMMETRIC: 'ASYMMETRIC' ;
AT : 'AT' ;
ATAN : 'ATAN' ;
AVG : 'AVG' ;
BASIS : 'BASIS' ;
BEGIN : 'BEGIN' ;
BEGIN_FRAME : 'BEGIN_FRAME' ;
BEGIN_PARTITION : 'BEGIN_PARTITION' ;
BETWEEN : 'BETWEEN' ;
BIGINT : 'BIGINT' ;
BOOLEAN : 'BOOLEAN' ;
BOOL_AND : 'BOOL_AND' ;
BOOL_OR : 'BOOL_OR' ;
BOTH : 'BOTH' ;
BY : 'BY' ;
CARDINALITY : 'CARDINALITY' ;
CASE : 'CASE' ;
CAST : 'CAST' ;
CEIL : 'CEIL' ;
CEILING : 'CEILING' ;
CENTURY : 'CENTURY' ;
CHARACTERISTICS : 'CHARACTERISTICS' ;
CHARACTERS : 'CHARACTERS' ;
CHARACTER_LENGTH : 'CHARACTER_LENGTH' ;
CHAR_LENGTH : 'CHAR_LENGTH' ;
COALESCE : 'COALESCE' ;
COMMIT : 'COMMIT' ;
COMMITTED : 'COMMITTED' ;
CONTAINS : 'CONTAINS' ;
COS : 'COS' ;
COSH : 'COSH' ;
COUNT : 'COUNT' ;
CROSS : 'CROSS' ;
CUME_DIST : 'CUME_DIST' ;
CURRENT : 'CURRENT' ;
CURRENT_DATABASE : 'CURRENT_DATABASE' ;
CURRENT_DATE : 'CURRENT_DATE' ;
CURRENT_ROW : 'CURRENT_ROW' ;
CURRENT_SCHEMA : 'CURRENT_SCHEMA' ;
CURRENT_SCHEMAS : 'CURRENT_SCHEMAS' ;
CURRENT_TIME : 'CURRENT_TIME' ;
CURRENT_TIMESTAMP : 'CURRENT_TIMESTAMP' ;
CURRENT_USER : 'CURRENT_USER' ;
DATE : 'DATE' ;
DATE_BIN : 'DATE_BIN' ;
DATE_TRUNC : 'DATE_TRUNC' ;
DAY : 'DAY' ;
DEC : 'DEC' ;
DECADE : 'DECADE' ;
DECIMAL : 'DECIMAL' ;
DEFAULT : 'DEFAULT' ;
DELETE : 'DELETE' ;
DENSE_RANK : 'DENSE_RANK' ;
DESC : 'DESC' ;
DISTINCT: 'DISTINCT' ;
DOUBLE : 'DOUBLE' ;
DURATION : 'DURATION' ;
ELSE : 'ELSE' ;
END : 'END' ;
END_FRAME : 'END_FRAME' ;
END_PARTITION : 'END_PARTITION' ;
EQUALS : 'EQUALS' ;
ERASE : 'ERASE' ;
ESCAPE : 'ESCAPE' ;
EVERY : 'EVERY' ;
EXCEPT : 'EXCEPT' ;
EXCLUDE : 'EXCLUDE' ;
EXISTS : 'EXISTS' ;
EXP : 'EXP' ;
EXTRACT : 'EXTRACT' ;
FALSE : 'FALSE' ;
FETCH : 'FETCH' ;
FIRST : 'FIRST' ;
FIRST_VALUE : 'FIRST_VALUE' ;
FLAG : 'FLAG' ;
FLOAT : 'FLOAT' ;
FLOOR : 'FLOOR' ;
FOLLOWING : 'FOLLOWING' ;
FOR : 'FOR' ;
FRAME_ROW : 'FRAME_ROW' ;
FROM : 'FROM' ;
FULL : 'FULL' ;
GENERATE_SERIES : 'GENERATE_SERIES' ;
GREATEST : 'GREATEST' ;
GROUP : 'GROUP' ;
GROUPS : 'GROUPS' ;
HAS_ANY_COLUMN_PRIVILEGE : 'HAS_ANY_COLUMN_PRIVILEGE' ;
HAS_SCHEMA_PRIVILEGE : 'HAS_SCHEMA_PRIVILEGE' ;
HAS_TABLE_PRIVILEGE : 'HAS_TABLE_PRIVILEGE' ;
HAVING : 'HAVING' ;
HOUR : 'HOUR' ;
IGNORE : 'IGNORE' ;
IMMEDIATELY : 'IMMEDIATELY' ;
IN : 'IN' ;
INNER : 'INNER' ;
INSERT : 'INSERT' ;
INT : 'INT' ;
INTEGER : 'INTEGER' ;
INTERSECT : 'INTERSECT' ;
INTERVAL : 'INTERVAL' ;
INTO : 'INTO' ;
IS : 'IS' ;
ISOLATION : 'ISOLATION' ;
JOIN : 'JOIN' ;
KEYWORD : 'KEYWORD' ;
LAG : 'LAG' ;
LAST : 'LAST' ;
LAST_VALUE : 'LAST_VALUE' ;
LATERAL : 'LATERAL' ;
LATEST : 'LATEST' ;
LEAD : 'LEAD' ;
LEADING : 'LEADING' ;
LEAST : 'LEAST' ;
LEFT : 'LEFT' ;
LENGTH : 'LENGTH' ;
LEVEL : 'LEVEL' ;
LIKE : 'LIKE' ;
LIKE_REGEX : 'LIKE_REGEX' ;
LIMIT : 'LIMIT' ;
LN : 'LN' ;
LOCAL : 'LOCAL' ;
LOCAL_NAME : 'LOCAL_NAME' ;
LOCALTIME : 'LOCALTIME' ;
LOCALTIMESTAMP : 'LOCALTIMESTAMP' ;
LOG : 'LOG' ;
LOG10 : 'LOG10' ;
LOWER : 'LOWER' ;
LOWER_INF : 'LOWER_INF' ;
MAX : 'MAX' ;
MICROSECOND : 'MICROSECOND' ;
MILLENNIUM : 'MILLENNIUM' ;
MILLISECOND : 'MILLISECOND' ;
MIN : 'MIN' ;
MINUTE : 'MINUTE' ;
MOD : 'MOD' ;
MONTH : 'MONTH' ;
NAMESPACE : 'NAMESPACE' ;
NANOSECOND : 'NANOSECOND' ;
NATURAL : 'NATURAL' ;
NEST_MANY : 'NEST_MANY' ;
NEST_ONE : 'NEST_ONE' ;
NEXT : 'NEXT' ;
NO : 'NO' ;
NONE : 'NONE';
NOT: 'NOT' ;
NOW : 'NOW' ;
NTH_VALUE : 'NTH_VALUE' ;
NTILE : 'NTILE' ;
NULL : 'NULL' ;
NULLIF : 'NULLIF' ;
NULLS : 'NULLS' ;
NUMERIC : 'NUMERIC' ;
OBJECT : 'OBJECT' ;
OCTETS : 'OCTETS' ;
OCTET_LENGTH : 'OCTET_LENGTH' ;
OF : 'OF' ;
OFFSET : 'OFFSET' ;
ON : 'ON' ;
ONLY : 'ONLY' ;
OR : 'OR' ;
ORDER : 'ORDER' ;
ORDINALITY : 'ORDINALITY' ;
OTHERS : 'OTHERS' ;
OUTER : 'OUTER' ;
OVER : 'OVER' ;
OVERLAPS : 'OVERLAPS' ;
OVERLAY : 'OVERLAY' ;
PARTITION : 'PARTITION' ;
PERCENT_RANK : 'PERCENT_RANK' ;
PERIOD : 'PERIOD' ;
PG_EXPANDARRAY : '_PG_EXPANDARRAY' ;
PG_GET_EXPR : 'PG_GET_EXPR' ;
PG_GET_INDEXDEF: 'PG_GET_INDEXDEF' ;
PLACING : 'PLACING' ;
PORTION : 'PORTION' ;
POSITION : 'POSITION' ;
POWER : 'POWER' ;
PRECEDES : 'PRECEDES' ;
PRECEDING : 'PRECEDING' ;
PRECISION : 'PRECISION' ;
QUARTER : 'QUARTER' ;
RANGE : 'RANGE' ;
RANGE_BINS : 'RANGE_BINS' ;
RANK : 'RANK' ;
READ : 'READ' ;
REAL : 'REAL' ;
RECORD : 'RECORD' ;
RECORDS : 'RECORDS' ;
RECURSIVE: 'RECURSIVE' ;
REGCLASS : 'REGCLASS' ;
REGPROC : 'REGPROC' ;
RENAME : 'RENAME' ;
REPEATABLE : 'REPEATABLE' ;
REPLACE : 'REPLACE' ;
RESPECT : 'RESPECT' ;
RETURNING : 'RETURNING';
RIGHT : 'RIGHT' ;
ROLE : 'ROLE' ;
ROLLBACK : 'ROLLBACK' ;
ROW : 'ROW' ;
ROWS : 'ROWS' ;
ROW_NUMBER : 'ROW_NUMBER' ;
SECOND : 'SECOND' ;
SELECT : 'SELECT' ;
SERIALIZABLE : 'SERIALIZABLE' ;
SESSION : 'SESSION' ;
SET : 'SET' ;
SETTING : 'SETTING' ;
SHOW : 'SHOW' ;
SIN : 'SIN' ;
SINH : 'SINH' ;
SMALLINT : 'SMALLINT' ;
SOME : 'SOME' ;
SQRT : 'SQRT' ;
STANDARD_CONFORMING_STRINGS : 'STANDARD_CONFORMING_STRINGS' ;
START : 'START' ;
STDDEV_POP : 'STDDEV_POP' ;
STDDEV_SAMP : 'STDDEV_SAMP' ;
STR : 'STR';
SUBMITTED : 'SUBMITTED' ;
SUBSTRING : 'SUBSTRING' ;
SUCCEEDS : 'SUCCEEDS' ;
SUM : 'SUM' ;
SYMMETRIC: 'SYMMETRIC' ;
SYSTEM_TIME : 'SYSTEM_TIME' ;
TAN : 'TAN' ;
TANH : 'TANH' ;
TEXT : 'TEXT' ;
THEN : 'THEN' ;
TIES : 'TIES' ;
TIME : 'TIME' ;
TIMESTAMP : 'TIMESTAMP' ;
TIMESTAMPTZ : 'TIMESTAMPTZ' ;
TIMEZONE : 'TIMEZONE' ;
TIMEZONE_HOUR : 'TIMEZONE_HOUR' ;
TIMEZONE_MINUTE : 'TIMEZONE_MINUTE' ;
TO : 'TO' ;
TRAILING : 'TRAILING' ;
TRANSACTION : 'TRANSACTION' ;
TRIM : 'TRIM' ;
TRIM_ARRAY : 'TRIM_ARRAY' ;
TRUE : 'TRUE' ;
TSTZRANGE : 'TSTZRANGE' ;
UNBOUNDED : 'UNBOUNDED' ;
UNCOMMITTED : 'UNCOMMITTED' ;
UNION : 'UNION' ;
UNKNOWN : 'UNKNOWN' ;
UNNEST : 'UNNEST' ;
UPDATE : 'UPDATE' ;
UPPER : 'UPPER' ;
UPPER_INF : 'UPPER_INF' ;
USING : 'USING' ;
UUID : 'UUID' ;
VALID_TIME : 'VALID_TIME' ;
VALUES : 'VALUES' ;
VALUE_OF : 'VALUE_OF' ;
VARCHAR : 'VARCHAR' ;
VAR_POP : 'VAR_POP' ;
VAR_SAMP : 'VAR_SAMP' ;
VERSION : 'VERSION' ;
WEEK : 'WEEK' ;
WHEN : 'WHEN' ;
WHERE : 'WHERE' ;
WINDOW : 'WINDOW' ;
WITH : 'WITH' ;
WITHOUT : 'WITHOUT' ;
WRITE : 'WRITE' ;
YEAR : 'YEAR' ;
ZONE : 'ZONE' ;

fragment DOLLAR_TAG_NAME : (LETTER | '_') (LETTER | DIGIT | '_' )* ;

DOLLAR_TAG : '$' DOLLAR_TAG_NAME? '$' {dollarTag = getText();} -> pushMode(DOLLAR_MODE);

REGULAR_IDENTIFIER : (LETTER | '_') (LETTER | DIGIT | '$' | '_' )* ;

DELIMITED_IDENTIFIER
    : '"' ('"' '"' | ~["/])* '"'
    | '`' ('`' '`' | ~[`/])* '`'
    ;

mode DOLLAR_MODE;

DM_END_TAG : '$' DOLLAR_TAG_NAME? '$' { dollarTag.equals(getText()) }? { dollarTag = null; } -> popMode;
DM_TEXT: (~'$')+ | '$' ;
