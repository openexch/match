sbe:
	java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED -Dsbe.generate.ir=true -Dsbe.target.language=Java -Dsbe.target.namespace=com.match.infrastructure.generated.sbe -Dsbe.output.dir=match/src/main/java -Dsbe.errorLog=yes -jar binaries/sbe-all-1.35.3.jar match/src/main/resources/sbe/order-schema.xml

# Docker Compose komutları
up:
	docker compose -f docker/docker-compose.yml up -d

down:
	docker compose -f docker/docker-compose.yml down

logs:
	docker compose -f docker/docker-compose.yml logs -f

# Yük testi komutları
loadtest:
	docker compose -f docker/docker-compose.yml exec loadtest java -cp /home/aeron/jar/cluster.jar com.match.LoadTestClient

loadtest-custom:
	@read -p "Orders per second: " ops; \
	read -p "Duration (ms): " duration; \
	docker compose -f docker/docker-compose.yml exec loadtest java -cp /home/aeron/jar/cluster.jar com.match.LoadTestClient $$ops $$duration

# Hızlı yük testi örnekleri
loadtest-1k:
	docker compose -f docker/docker-compose.yml exec loadtest java -cp /home/aeron/jar/cluster.jar com.match.LoadTestClient 1000 60000

loadtest-5k:
	docker compose -f docker/docker-compose.yml exec loadtest java -cp /home/aeron/jar/cluster.jar com.match.LoadTestClient 5000 60000

loadtest-10k:
	docker compose -f docker/docker-compose.yml exec loadtest java -cp /home/aeron/jar/cluster.jar com.match.LoadTestClient 10000 60000

# Cluster durumu kontrolü
status:
	docker compose -f docker/docker-compose.yml ps

# Temizlik
clean:
	docker compose -f docker/docker-compose.yml down -v
	docker system prune -f