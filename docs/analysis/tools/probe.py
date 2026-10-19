import sys, random; print(sys.version); 
try:
    random.randint(int(0.01*-100), int(0.5*-100))
except Exception as e:
    print("randint(neg) ->", type(e).__name__, e)
print("round(0.005,2)=", round(0.005,2))
print("round(0.0049,2)=", round(0.0049,2))
import sqlite3
c=sqlite3.connect(":memory:"); c.execute("create table t(s integer default 0)"); c.execute("insert into t values(1000)")
c.execute("update t set s=s-? where rowid=1", ("铭纹境圆满",)); print("stone after string deduct:", c.execute("select s from t").fetchone()[0])
c.execute("update t set s=s-? where rowid=1", ("100",)); print("stone after str-num deduct:", c.execute("select s from t").fetchone()[0])
