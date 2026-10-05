pipeline {
    agent any

    options {
        skipDefaultCheckout(true)
        timestamps()
        ansiColor('xterm')
        disableConcurrentBuilds()
        timeout(time: 45, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '10'))
    }

    parameters {
        string(name: 'DOCKER_REGISTRY', defaultValue: 'docker.io', description: '镜像仓库地址，例如 docker.io')
        string(name: 'DOCKER_NAMESPACE', defaultValue: 'replace-me', description: '镜像仓库命名空间，只允许小写字符')
        string(name: 'DOCKER_CREDENTIALS_ID', defaultValue: 'dockerhub-blog-system', description: 'Jenkins Docker 用户名密码凭据 ID')
        booleanParam(name: 'PUSH_IMAGES', defaultValue: true, description: '测试和构建通过后推送镜像')
        booleanParam(name: 'SYNC_BLOG_CONTENT', defaultValue: false, description: '构建 Hexo 前从公开 API 同步文章')
        string(name: 'BLOG_PUBLIC_API_URL', defaultValue: '', description: '文章公开 API 地址，仅在同步文章时使用')
        string(name: 'BLOG_SITE_URL', defaultValue: '', description: '线上站点地址，例如 https://blog.example.com')
        string(name: 'BLOG_OWNER_PHONE', defaultValue: '', description: '管理端构建所需的博主手机号，不要填写密码')
    }

    environment {
        MAVEN_OPTS = '-Dmaven.repo.local=.m2/repository -Dstyle.color=never'
        DOCKER_BUILDKIT = '1'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Toolchain') {
            steps {
                sh '''
                    set -eu
                    java -version
                    mvn -version
                    node --version
                    node -e 'const [major, minor] = process.versions.node.split(".").map(Number); if (major < 22 || (major === 22 && minor < 12)) throw new Error("blog-web requires Node.js >= 22.12")'
                    npm --version
                    docker version --format '{{.Server.Version}}'
                '''
            }
        }

        stage('Database migration tests') {
            steps {
                sh 'node --test tools/database/*.test.mjs'
            }
        }

        stage('Backend test and package') {
            steps {
                dir('blog-server') {
                    sh 'mvn -B -ntp clean test package'
                }
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
                    def namespace = params.DOCKER_NAMESPACE.trim().replaceAll('^/+', '').replaceAll('/+$', '')
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
                    echo "Building images with tag ${env.IMAGE_TAG}; latest=${env.PUSH_LATEST}"
                }
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
                        printf '%s' "$DOCKER_PASSWORD" | docker login "$DOCKER_REGISTRY" --username "$DOCKER_USERNAME" --password-stdin
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
                        docker logout "$DOCKER_REGISTRY" >/dev/null 2>&1 || true
                    '''
                }
            }
        }

        stage('Archive artifacts') {
            steps {
                archiveArtifacts artifacts: 'blog-server/target/*.jar,blog-web/dist/**,blog-admin/dist/**', fingerprint: true
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
