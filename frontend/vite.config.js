// 개발 서버에서만 API·업로드 사진 경로를 로컬 Spring 서버로 전달한다. 배포 빌드는 정적 파일을 JAR에 포함한다.
import {defineConfig} from 'vite';import react from '@vitejs/plugin-react';export default defineConfig({plugins:[react()],server:{proxy:{'/api':'http://127.0.0.1:8080','/media':'http://127.0.0.1:8080'}}});
