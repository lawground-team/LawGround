# 운영 자동 배포

GitHub의 main push CI가 모두 성공하면, 그 실행에서 빌드한 Spring JAR와 같은 커밋의 Python 파일을 배포 번들로 게시한다. Ubuntu는 systemd 타이머로 main의 현재 커밋을 확인하고, 해당 push의 CI가 성공한 경우에만 GitHub Release에서 번들을 HTTPS로 받는다. Release에 서버의 환경변수, 데이터, 모델 캐시를 넣지 않는다.

기존 LawGround 운영 호스트에서 현재 실행 중인 커밋을 넘겨 한 번 설치한다.

    sudo bash ops/install-pull-deploy.sh <currently-deployed-git-sha>

운영 호스트와 배포 로그는 아래 명령으로 확인한다.

    systemctl list-timers lawground-pull-deploy.timer
    sudo /usr/local/libexec/lawground-pull-deploy --check
    sudo systemctl start lawground-pull-deploy.service
    sudo journalctl -u lawground-pull-deploy.service -n 60
    readlink /srv/dis-docker-data/lawground-deploy/current

새 앱의 AI와 웹 준비 상태를 확인한다. 실패하면 직전 앱을 다시 실행하고, 같은 CI 실행을 반복 적용하지 않는다. Flyway 파일이 달라지면 재시작 전에 PostgreSQL을 백업한다. 이미 적용한 스키마 변경은 자동으로 되돌리지 않으므로, 운영 스키마 변경은 이전 앱과 호환되게 설계한다.

배포를 일시 중지하려면 타이머를 정지한다. 배포 스크립트나 systemd 설정 변경은 운영 호스트에서 설치 스크립트를 다시 실행해 반영한다. 서버에서 실행하는 배포기 자체는 CI 번들로 갱신하지 않는다.
