IMAGE_NAME ?= causa-mcp-server
IMAGE_TAG  ?= latest

.PHONY: build image load deploy port-forward

## Build the JAR
build:
	./mvnw clean package -DskipTests

## Build the container image
image: build
	docker build -f src/main/docker/Dockerfile.jvm -t $(IMAGE_NAME):$(IMAGE_TAG) .

## Load the image into the Kind cluster
load: image
	kind load docker-image $(IMAGE_NAME):$(IMAGE_TAG)

## Deploy to the Kind cluster
deploy:
	kubectl apply -f manifests/causa-mcp-server.yaml

## Port-forward the MCP server to localhost:8081
port-forward:
	kubectl port-forward -n causa svc/causa-mcp-server 8081:8081
