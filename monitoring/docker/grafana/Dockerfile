FROM grafana/grafana:11.3.0

USER root
COPY docker/grafana/entrypoint.sh /entrypoint.sh
COPY grafana/dashboards /var/lib/grafana/dashboards
RUN chmod +x /entrypoint.sh

EXPOSE 3000
ENTRYPOINT ["/entrypoint.sh"]
