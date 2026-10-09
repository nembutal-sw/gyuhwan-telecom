#!/usr/bin/env python3
"""Convert a privately exported D1/SQLite backup to a JSON import; never fetch credentials."""
# 이미 받은 SQLite 백업을 읽기 전용으로 열어 이전 상담 자료만 JSON으로 내보낸다.
# 운영 가격은 DB 원문과 공개 API의 실효값이 다를 수 있으므로 별도로 검증해 이전한다.
import argparse,sqlite3,json,os
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('database',help='Read-only exported SQLite database');p.add_argument('output');args=p.parse_args()
db=sqlite3.connect(Path(args.database).resolve().as_uri()+'?mode=ro',uri=True);db.row_factory=sqlite3.Row
names={r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'")};data={}
for table in ['inquiries','consultations']:
 if table in names:data[table]=[dict(r) for r in db.execute(f'SELECT * FROM {table}')]
# Raw DB config is not automatically imported: old policy refreshers may change its effective values.
# Import current /api/config JSON separately to preserve the actual displayed policies.
out=Path(args.output);fd=os.open(out,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
with os.fdopen(fd,'w',encoding='utf-8') as f:json.dump(data,f,ensure_ascii=False,indent=2)
print('Export created. Contains personal data; keep private and delete after verified migration.')
