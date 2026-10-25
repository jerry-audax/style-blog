pipeline {
    agent { label 'linux-docker' }

    options {
        skipDefaultCheckout(true)
        timestamps()
        disableConcurrentBuilds()
        timeout(time: 45, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '2'))
    }

    parameters {
        string(name: 'DOCKER_REGISTRY', defaultValue: 'docker.io', description: '镜像仓库地址，例如 docker.io')
        string(name: 'DOCKER_NAMESPACE', defaultValue: '', description: '镜像仓库用户名或组织名，不是 GitHub 源码仓库名；推送时必填')
        string(name: 'DOCKER_CREDENTIALS_ID', defaultValue: 'dockerhub-blog-system', description: 'Jenkins Docker 用户名密码凭据 ID')
        booleanParam(name: 'PUSH_IMAGES', defaultValue: false, description: '测试和构建通过后推送镜像；仅 main/master 允许推送')
        booleanParam(name: 'SYNC_BLOG_CONTENT', defaultValue: false, description: '构建 Hexo 前从公开 API 同步文章')
        string(name: 'BLOG_PUBLIC_API_URL', defaultValue: '', description: '文章公开 API 地址，仅在同步文章时使用')
        string(name: 'BLOG_SITE_URL', defaultValue: 'https://blog.jrtitan.xyz', description: '公开站点地址')
        string(name: 'BLOG_OWNER_PHONE', defaultValue: '19140838906', description: '单博主登录账号，不是密码')
    }

    environment {
        MAVEN_OPTS = '-Dfile.encoding=UTF-8 -Dstyle.color=never'
        DOCKER_BUILDKIT = '1'
        MEDIA_MIGRATION_TEST = '0'
        IMGBED_SMOKE = 'false'
        MUSIC_SMOKE = 'false'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Toolchain') {
            steps {
                script {
                    def branch = (env.BRANCH_NAME ?: env.GIT_BRANCH ?: '').replaceFirst(/^origin\//, '')
                    if (params.PUSH_IMAGES && !(branch in ['main', 'master'])) {
                        error('Only main/master may publish images; task branches build and test only')
                    }
                    def registry = params.DOCKER_REGISTRY.trim()
                    def namespace = params.DOCKER_NAMESPACE.trim()
                    if (!(registry ==~ /[a-z0-9][a-z0-9.-]*(:[0-9]+)?/)) {
                        error('DOCKER_REGISTRY must be a registry host, optionally with port; no URL scheme')
                    }
                    if (namespace && !(namespace ==~ /[a-z0-9]+([._-][a-z0-9]+)*(\/[a-z0-9]+([._-][a-z0-9]+)*)*/)) {
                        error('Invalid Docker namespace')
                    }
                    if (params.PUSH_IMAGES && (!namespace || !params.DOCKER_CREDENTIALS_ID.trim())) {
                        error('Image publishing requires DOCKER_NAMESPACE and DOCKER_CREDENTIALS_ID')
                    }
                    if (!(params.BLOG_SITE_URL.trim() ==~ /https?:\/\/[^\s]+/)
                        || !(params.BLOG_OWNER_PHONE.trim() ==~ /[0-9]+/)) {
                        error('Set a valid BLOG_SITE_URL and numeric BLOG_OWNER_PHONE')
                    }
                }
                sh '''
                    set -eu
                    java -version
                    mvn -version
                    node --version
                    pwsh -NoProfile -Command '$PSVersionTable.PSVersion.ToString(); if ($PSVersionTable.PSVersion.Major -lt 7) { exit 1 }'
                    node -e 'const [major, minor, patch] = process.versions.node.split(".").map(Number); if (major !== 22 || minor < 23 || (minor === 23 && patch < 3)) throw new Error("Use Node.js >=22.23.3 <23, matching the Dockerfiles")'
                    npm --version
                    docker version --format '{{.Server.Version}}'
                '''
            }
        }

        stage('Database migration tests') {
            steps {
                sh 'node blog-server/tools/architecture/verify-migration-names.mjs'
                sh 'node --test blog-server/tools/database/*.test.mjs'
                sh 'node --test deploy/ci/*.test.mjs'
            }
        }

        stage('Backend test and package') {
            steps {
                sh 'node blog-server/tools/architecture/verify-test-supply.mjs'
                sh 'bash deploy/ci/test-backend.sh'
                sh 'node blog-server/tools/architecture/verify-test-supply.mjs --reports'
                sh 'node --test blog-server/tools/architecture/*.test.mjs'
            }
        }

        stage('Music runtime contract tests') {
            steps {
                dir('blog-server/music-runtime') {
                    sh 'npm ci --ignore-scripts --no-audit --no-fund'
                    sh 'npm test'
                }
            }
        }

        stage('Frontend test and build') {
            parallel {
                stage('Public Vue and Hexo') {
                    steps {
                        dir('blog-web') {
                            sh 'npm ci --no-audit --no-fund'
                            sh 'npm test'
                            script {
                                if (params.SYNC_BLOG_CONTENT && !params.BLOG_PUBLIC_API_URL.trim()) {
                                    error('BLOG_PUBLIC_API_URL is required when SYNC_BLOG_CONTENT is enabled')
                                }
                                withEnv([
                                    "BLOG_API_URL=${params.BLOG_PUBLIC_API_URL.trim()}",
                                    "BLOG_SITE_URL=${params.BLOG_SITE_URL.trim()}"
                                ]) {
                                    if (params.SYNC_BLOG_CONTENT) {
                                        sh 'npm run content:sync'
                                    }
                                    sh 'npm run build'
                                }
                            }
                        }
                    }
                }
                stage('Admin Vue') {
                    steps {
                        dir('blog-admin') {
                            sh 'npm ci --legacy-peer-deps --no-audit --no-fund'
                            sh 'npm test'
                            withEnv([
                                "VITE_BLOG_OWNER_PHONE=${params.BLOG_OWNER_PHONE.trim()}",
                                "VITE_BLOG_SITE_URL=${params.BLOG_SITE_URL.trim()}"
                            ]) {
                                sh 'npm run build'
                            }
                        }
                    }
                }
            }
        }

        stage('Prepare image metadata') {
            steps {
                script {
                    def revision = sh(returnStdout: true, script: 'git rev-parse --short=12 HEAD').trim()
                    def registry = params.DOCKER_REGISTRY.trim().replaceAll('/+$', '')
                    def namespace = params.DOCKER_NAMESPACE.trim() ?: 'style-blog-local'
                    if (!registry || !namespace) {
                        error('DOCKER_REGISTRY and DOCKER_NAMESPACE must not be empty')
                    }
                    if (!(namespace ==~ /[a-z0-9._\/-]+/)) {
                        error('DOCKER_NAMESPACE must contain only lower-case letters, digits, dot, underscore, slash, or hyphen')
                    }
                    env.IMAGE_TAG = "${env.BUILD_NUMBER}-${revision}"
                    env.IMAGE_BACKEND = "${registry}/${namespace}/blog-system-server"
                    env.IMAGE_WEB = "${registry}/${namespace}/blog-system-web"
                    env.IMAGE_ADMIN = "${registry}/${namespace}/blog-system-admin"
                    env.PUSH_LATEST = ((env.BRANCH_NAME ?: env.GIT_BRANCH ?: '').replaceFirst(/^origin\//, '') in ['main', 'master']).toString()
                    env.REGISTRY_HOST = registry
                    echo "Building images with tag ${env.IMAGE_TAG}; latest=${env.PUSH_LATEST}"
                }
                sh '''
                    mkdir -p ci-artifacts
                    printf 'BLOG_SERVER_IMAGE=%s:%s\nBLOG_WEB_IMAGE=%s:%s\nBLOG_ADMIN_IMAGE=%s:%s\n' \\
                        "$IMAGE_BACKEND" "$IMAGE_TAG" "$IMAGE_WEB" "$IMAGE_TAG" "$IMAGE_ADMIN" "$IMAGE_TAG" \\
                        > ci-artifacts/images.env
                    git rev-parse HEAD > ci-artifacts/source-commit.txt
                '''
            }
        }

        stage('Build Docker images') {
            parallel {
                stage('Backend image') {
                    steps {
                        sh 'docker build --pull -t "$IMAGE_BACKEND:$IMAGE_TAG" blog-server'
                    }
                }
                stage('Public Vue and Hexo image') {
                    steps {
                        withEnv(["BLOG_SITE_URL=${params.BLOG_SITE_URL.trim()}"]) {
                            sh 'docker build --pull --build-arg BLOG_SITE_URL="$BLOG_SITE_URL" -t "$IMAGE_WEB:$IMAGE_TAG" blog-web'
                        }
                    }
                }
                stage('Admin Vue image') {
                    steps {
                        withEnv([
                            "VITE_BLOG_OWNER_PHONE=${params.BLOG_OWNER_PHONE.trim()}",
                            "VITE_BLOG_SITE_URL=${params.BLOG_SITE_URL.trim()}"
                        ]) {
                            sh 'docker build --pull --build-arg VITE_BLOG_OWNER_PHONE="$VITE_BLOG_OWNER_PHONE" --build-arg VITE_BLOG_SITE_URL="$VITE_BLOG_SITE_URL" -t "$IMAGE_ADMIN:$IMAGE_TAG" blog-admin'
                        }
                    }
                }
            }
        }

        stage('Push Docker images') {
            when {
                expression { return params.PUSH_IMAGES }
            }
            steps {
                withCredentials([usernamePassword(
                    credentialsId: params.DOCKER_CREDENTIALS_ID,
                    usernameVariable: 'DOCKER_USERNAME',
                    passwordVariable: 'DOCKER_PASSWORD'
                )]) {
                    sh '''
                        set -eu
                        set +x
                        # 每次构建独立登录目录，不覆盖构建节点其他任务的 Docker 凭据。
                        DOCKER_CONFIG=$(mktemp -d)
                        export DOCKER_CONFIG
                        trap 'rm -rf "$DOCKER_CONFIG"' EXIT
                        printf '%s' "$DOCKER_PASSWORD" | docker login "$REGISTRY_HOST" --username "$DOCKER_USERNAME" --password-stdin
                        set -x
                        docker push "$IMAGE_BACKEND:$IMAGE_TAG"
                        docker push "$IMAGE_WEB:$IMAGE_TAG"
                        docker push "$IMAGE_ADMIN:$IMAGE_TAG"
                        if [ "$PUSH_LATEST" = "true" ]; then
                            docker tag "$IMAGE_BACKEND:$IMAGE_TAG" "$IMAGE_BACKEND:latest"
                            docker tag "$IMAGE_WEB:$IMAGE_TAG" "$IMAGE_WEB:latest"
                            docker tag "$IMAGE_ADMIN:$IMAGE_TAG" "$IMAGE_ADMIN:latest"
                            docker push "$IMAGE_BACKEND:latest"
                            docker push "$IMAGE_WEB:latest"
                            docker push "$IMAGE_ADMIN:latest"
                        fi
                    '''
                }
            }
        }

        stage('Archive artifacts') {
            steps {
                sh '''
                    tar -czf ci-artifacts/blog-web.tar.gz -C blog-web/dist .
                    tar -czf ci-artifacts/blog-admin.tar.gz -C blog-admin/dist .
                '''
                archiveArtifacts artifacts: 'blog-server/blog-app/target/blog-app.jar,ci-artifacts/*', fingerprint: true
            }
        }
    }

    post {
        always {
            junit allowEmptyResults: true, testResults: '**/target/surefire-reports/*.xml,**/target/failsafe-reports/*.xml'
        }
        cleanup {
            deleteDir()
        }
    }
}
