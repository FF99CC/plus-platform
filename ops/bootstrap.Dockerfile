FROM python:3.12.13-alpine3.22

RUN pip install --no-cache-dir bcrypt==4.3.0
WORKDIR /app
COPY ops/bootstrap.py /app/bootstrap.py
COPY backend/script/sql/ry_vue.sql /seed/ry_vue.sql
COPY backend/script/sql/ry_workflow.sql /seed/ry_workflow.sql

CMD ["python", "/app/bootstrap.py"]
