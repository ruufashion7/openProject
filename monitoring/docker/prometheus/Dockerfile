FROM prom/prometheus:v2.55.1

COPY docker/prometheus/prometheus.yml.template /etc/prometheus/prometheus.yml.template
COPY docker/prometheus/entrypoint.sh /entrypoint.sh
USER root
RUN chmod +x /entrypoint.sh

EXPOSE 9090
ENTRYPOINT ["/entrypoint.sh"]
