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
        string(name: 'DOCKER_REGISTRY', defaultValue: 'docker.io', description: 'Registry hostname, for example docker.io or registry.example.com')
        string(name: 'DOCKER_NAMESPACE', defaultValue: 'replace-me', description: 'Lower-case registry namespace or organization')
        string(name: 'DOCKER_CREDENTIALS_ID', defaultValue: 'dockerhub-blog-system', description: 'Jenkins username/password credential ID used for docker login')
        booleanParam(name: 'PUSH_IMAGES', defaultValue: true, description: 'Push the application images after tests and image builds pass')
        booleanParam(name: 'SYNC_BLOG_CONTENT', defaultValue: false, description: 'Export public published articles before building Hexo; requires an accessible public read API')
        string(name: 'BLOG_PUBLIC_API_URL', defaultValue: '', description: 'Public Spring Boot origin used by content export; no admin credentials')
        string(name: 'BLOG_SITE_URL', defaultValue: '', description: 'Public website origin used by Hexo canonical/search URLs, for example https://blog.example.com')
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
                    node -e 'const [major, minor] = process.versions.node.split(".").map(Number); if (major < 22 || (major === 22 && minor < 12)) throw new Error("Public web build requires Node.js >= 22.12")'
                    npm --version
                    docker version --format '{{.Server.Version}}'
                '''
            }
        }

        stage('Database migration tool tests') {
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

        stage('AI service test and package') {
            steps {
                dir('blog-ai') {
                    sh 'mvn -B -ntp clean test package'
                }
            }
        }

        stage('Frontend test and build') {
            parallel {
                stage('Public web') {
                    steps {
                        dir('blog-web') {
                            sh 'npm ci --no-audit --no-fund'
                            sh 'npm test'
                            script {
                                withEnv(["BLOG_API_URL=${params.BLOG_PUBLIC_API_URL.trim()}", "BLOG_SITE_URL=${params.BLOG_SITE_URL.trim()}"]) {
                                    if (params.SYNC_BLOG_CONTENT) {
                                        if (!params.BLOG_PUBLIC_API_URL.trim()) error('BLOG_PUBLIC_API_URL is required when SYNC_BLOG_CONTENT is enabled')
                                        sh 'npm run content:sync'
                                    }
                                    sh 'npm run build'
                                }
                            }
                        }
                    }
                }
                stage('Admin web') {
                    steps {
                        dir('blog-admin') {
                            sh 'npm ci --legacy-peer-deps --no-audit --no-fund'
                            sh 'npm test'
                            withEnv(["VITE_BLOG_SITE_URL=${params.BLOG_SITE_URL.trim()}"]) {
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
                    def safeRegistry = params.DOCKER_REGISTRY.trim().replaceAll('/+$', '')
                    def safeNamespace = params.DOCKER_NAMESPACE.trim().replaceAll('^/+', '').replaceAll('/+$', '')

                    if (!safeRegistry || !safeNamespace) {
                        error('DOCKER_REGISTRY and DOCKER_NAMESPACE must not be empty')
                    }
                    if (!(safeNamespace ==~ /[a-z0-9._\/-]+/)) {
                        error('DOCKER_NAMESPACE must contain only lower-case letters, digits, dot, underscore, slash, or hyphen')
                    }

                    env.IMAGE_TAG = "${env.BUILD_NUMBER}-${revision}"
                    env.IMAGE_BACKEND = "${safeRegistry}/${safeNamespace}/blog-system-blog-server"
                    env.IMAGE_AI = "${safeRegistry}/${safeNamespace}/blog-system-blog-ai"
                    env.IMAGE_WEB = "${safeRegistry}/${safeNamespace}/blog-system-blog-web"
                    env.IMAGE_ADMIN = "${safeRegistry}/${safeNamespace}/blog-system-blog-admin"
                    env.IMAGE_PUBLISHER = "${safeRegistry}/${safeNamespace}/blog-system-blog-publisher"
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
                stage('AI image') {
                    steps {
                        sh 'docker build --pull -t "$IMAGE_AI:$IMAGE_TAG" blog-ai'
                    }
                }
                stage('Public web image') {
                    steps {
                        withEnv(["BLOG_SITE_URL=${params.BLOG_SITE_URL.trim()}"]) {
                            sh 'docker build --pull --build-arg BLOG_SITE_URL="$BLOG_SITE_URL" -t "$IMAGE_WEB:$IMAGE_TAG" blog-web'
                        }
                    }
                }
                stage('Admin web image') {
                    steps {
                        withEnv(["VITE_BLOG_SITE_URL=${params.BLOG_SITE_URL.trim()}"]) {
                            sh 'docker build --pull --build-arg VITE_BLOG_SITE_URL="$VITE_BLOG_SITE_URL" -t "$IMAGE_ADMIN:$IMAGE_TAG" blog-admin'
                        }
                    }
                }
                stage('Publication worker image') {
                    steps {
                        withEnv(["BLOG_SITE_URL=${params.BLOG_SITE_URL.trim()}"]) {
                            sh 'docker build --pull --target publisher --build-arg BLOG_SITE_URL="$BLOG_SITE_URL" -t "$IMAGE_PUBLISHER:$IMAGE_TAG" blog-web'
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
                        docker push "$IMAGE_AI:$IMAGE_TAG"
                        docker push "$IMAGE_WEB:$IMAGE_TAG"
                        docker push "$IMAGE_ADMIN:$IMAGE_TAG"
                        docker push "$IMAGE_PUBLISHER:$IMAGE_TAG"

                        if [ "$PUSH_LATEST" = "true" ]; then
                            docker tag "$IMAGE_BACKEND:$IMAGE_TAG" "$IMAGE_BACKEND:latest"
                            docker tag "$IMAGE_AI:$IMAGE_TAG" "$IMAGE_AI:latest"
                            docker tag "$IMAGE_WEB:$IMAGE_TAG" "$IMAGE_WEB:latest"
                            docker tag "$IMAGE_ADMIN:$IMAGE_TAG" "$IMAGE_ADMIN:latest"
                            docker tag "$IMAGE_PUBLISHER:$IMAGE_TAG" "$IMAGE_PUBLISHER:latest"
                            docker push "$IMAGE_BACKEND:latest"
                            docker push "$IMAGE_AI:latest"
                            docker push "$IMAGE_WEB:latest"
                            docker push "$IMAGE_ADMIN:latest"
                            docker push "$IMAGE_PUBLISHER:latest"
                        fi

                        docker logout "$DOCKER_REGISTRY" >/dev/null 2>&1 || true
                    '''
                }
            }
        }

        stage('Archive artifacts') {
            steps {
            archiveArtifacts artifacts: 'blog-server/target/*.jar,blog-ai/target/*.jar,blog-web/dist/**,blog-admin/dist/**', fingerprint: true
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
