# 배포 그림 원본

`build-diagrams.mjs`가 편집 가능한 SVG 생성 원본이고 `runtime.svg`, `delivery.svg`가 GitHub용 self-contained 출력이다. `node docs/architecture/build-diagrams.mjs`로 재생성한다. `runtime.eraserdiagram`은 같은 운영 경계를 나타내는 Eraser용 편집 원본이다. 다이어그램은 운영 요청 경로와 배포 제어 경로를 분리한다. GitHub 권한/배포 실제 실행 여부는 [배포 파이프라인](../DEPLOYMENT_PIPELINE.md)의 최신 상태를 따른다.

아이콘은 원본 파일을 변경하지 않고 축소 배치했다. 외부 URL을 SVG에 남기지 않는다.

- CloudFront / EC2 / S3: [AWS Labs architecture icons](https://github.com/awslabs/aws-icons-for-plantuml), AWS. 함께 보존한 `icons/AWS-LICENSE`(CC BY-ND 2.0)를 따른다.
- nginx / PostgreSQL / Spring Boot / GitHub: [Simple Icons](https://github.com/simple-icons/simple-icons). 함께 보존한 `icons/SIMPLE-ICONS-LICENSE`를 따른다. 각 로고/상표 권리는 해당 소유자에게 있다.
- 수신일: 2026-09-20. 그림은 해당 업체의 보증이나 제휴를 뜻하지 않는다.
