FROM centrifugal/centrifugo:v6.9.6

COPY fly-config.json /etc/centrifugo/config.json

EXPOSE 8000

CMD ["centrifugo", "--config", "/etc/centrifugo/config.json"]
