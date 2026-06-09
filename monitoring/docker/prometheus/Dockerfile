FROM prom/prometheus:v2.55.1

USER root
RUN apk add --no-cache gettext

COPY docker/prometheus/prometheus.yml.template /etc/prometheus/prometheus.yml.template
COPY docker/prometheus/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

EXPOSE 9090
ENTRYPOINT ["/entrypoint.sh"]
