# Phase 1 — API Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Author `itemtree-api.yaml`, configure the openapi-generator Gradle plugin, generate Spring interfaces + DTOs into `com.myxcomp.ice.xtree.generated`, create four stub controllers, and verify `/v3/api-docs` returns the spec with the correct title.

**Architecture:** The openapi-generator plugin reads `src/main/resources/openapi/itemtree-api.yaml` at build time and emits `*Api` interfaces and model DTOs into `build/generated/openapi/src/main/java`. The generated code is added to the main source set. Hand-written stub controllers in `api/controller/` implement those interfaces (throwing `UnsupportedOperationException`). The generated `SpringDocConfiguration` bean (produced as a supporting file) supplies the API title; springdoc-openapi scans the controllers and serves the spec at `/v3/api-docs`.

**Tech Stack:** OpenAPI 3.0.3, openapi-generator-gradle-plugin 7.10.0, Spring Boot 3.4.1 (`spring` generator with `interfaceOnly=true`), springdoc-openapi-starter-webmvc-ui 2.8.6, JUnit 5 + MockMvc.

---

## File map

| Action | Path |
|--------|------|
| Create | `src/main/resources/openapi/itemtree-api.yaml` |
| Modify | `build.gradle.kts` — add `openApiGenerate`, source set, and `compileJava` dependency |
| Create | `src/main/java/com/myxcomp/ice/xtree/api/controller/ItemController.java` |
| Create | `src/main/java/com/myxcomp/ice/xtree/api/controller/TreeController.java` |
| Create | `src/main/java/com/myxcomp/ice/xtree/api/controller/SearchController.java` |
| Create | `src/main/java/com/myxcomp/ice/xtree/api/controller/UserController.java` |
| Create | `src/test/java/com/myxcomp/ice/xtree/ApiContractTest.java` |

Generated (not hand-written, do not edit):
- `build/generated/openapi/src/main/java/com/myxcomp/ice/xtree/generated/api/ItemsApi.java`
- `build/generated/openapi/src/main/java/com/myxcomp/ice/xtree/generated/api/TreeApi.java`
- `build/generated/openapi/src/main/java/com/myxcomp/ice/xtree/generated/api/SearchApi.java`
- `build/generated/openapi/src/main/java/com/myxcomp/ice/xtree/generated/api/UsersApi.java`
- `build/generated/openapi/src/main/java/com/myxcomp/ice/xtree/generated/model/*.java` (9 DTOs)
- `build/generated/openapi/src/main/java/com/myxcomp/ice/xtree/generated/api/SpringDocConfiguration.java`

---

## Task 1: Write the acceptance test (failing first)

**Files:**
- Create: `src/test/java/com/myxcomp/ice/xtree/ApiContractTest.java`

- [ ] **Step 1: Create the test file**

```java
package com.myxcomp.ice.xtree;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ApiContractTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void apiDocsReturnsOkWithCorrectTitle() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("ItemTree API"))
                .andExpect(jsonPath("$.openapi").value("3.0.3"));
    }

    @Test
    void apiDocsContainsAllTenOperations() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"createItem\"");
        assertThat(body).contains("\"deleteItem\"");
        assertThat(body).contains("\"moveItem\"");
        assertThat(body).contains("\"renameItem\"");
        assertThat(body).contains("\"updateItemData\"");
        assertThat(body).contains("\"getItems\"");
        assertThat(body).contains("\"getTree\"");
        assertThat(body).contains("\"getSubtree\"");
        assertThat(body).contains("\"search\"");
        assertThat(body).contains("\"getHomeFolder\"");
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails**

```
./gradlew test --tests "com.myxcomp.ice.xtree.ApiContractTest"
```

Expected: `apiDocsReturnsOkWithCorrectTitle` FAILS because springdoc returns the default title `"OpenAPI definition"` (the YAML hasn't been written yet). `apiDocsContainsAllTenOperations` may also fail because no controllers exist. The existing `ItemTreeApplicationTests` must still PASS.

- [ ] **Step 3: Commit the failing test**

```bash
git add src/test/java/com/myxcomp/ice/xtree/ApiContractTest.java
git commit -m "test(phase1): add acceptance tests for /v3/api-docs title and operationIds"
```

---

## Task 2: Configure the openapi-generator Gradle plugin

**Files:**
- Modify: `build.gradle.kts`

The plugin `org.openapi.generator` is already declared in `plugins {}` at version `7.10.0` but has no configuration. Add the task configuration, source set wiring, and compile dependency.

- [ ] **Step 1: Append to `build.gradle.kts`**

Add the following block at the **end** of `build.gradle.kts` (after the existing `tasks.withType<JavaCompile>` block):

```kotlin
openApiGenerate {
    generatorName.set("spring")
    inputSpec.set("$projectDir/src/main/resources/openapi/itemtree-api.yaml")
    outputDir.set(layout.buildDirectory.dir("generated/openapi").get().asFile.absolutePath)
    apiPackage.set("com.myxcomp.ice.xtree.generated.api")
    modelPackage.set("com.myxcomp.ice.xtree.generated.model")
    configOptions.set(mapOf(
        "interfaceOnly"          to "true",
        "useSpringBoot3"         to "true",
        "openApiNullable"        to "false",
        "dateLibrary"            to "java8",
        "hideGenerationTimestamp" to "true"
    ))
}

sourceSets {
    main {
        java {
            srcDir(layout.buildDirectory.dir("generated/openapi/src/main/java"))
        }
    }
}

tasks.compileJava {
    dependsOn(tasks.openApiGenerate)
}
```

- [ ] **Step 2: Commit**

```bash
git add build.gradle.kts
git commit -m "build(phase1): configure openapi-generator for spring interface generation"
```

---

## Task 3: Author the OpenAPI YAML spec

**Files:**
- Create: `src/main/resources/openapi/itemtree-api.yaml`

The full paths (e.g. `/api/v1/itemtree/items`) are embedded directly in the `paths:` object — this causes the generator to emit `@RequestMapping("/api/v1/itemtree/items")` etc. on the interfaces, so no `server.servlet.context-path` change is needed and `/v3/api-docs` stays at the root.

- [ ] **Step 1: Create `src/main/resources/openapi/itemtree-api.yaml`**

```yaml
openapi: 3.0.3
info:
  title: ItemTree API
  description: REST service fronting the ITEMTREE Oracle table.
  version: 1.0.0

tags:
  - name: items
    description: Item CRUD operations
  - name: tree
    description: Tree view operations
  - name: search
    description: Search operations
  - name: users
    description: User operations

paths:

  /api/v1/itemtree/items:
    post:
      tags: [items]
      operationId: createItem
      summary: Create item
      parameters:
        - $ref: '#/components/parameters/XIceUser'
        - $ref: '#/components/parameters/XImpersonatedUser'
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/CreateItemRequest'
      responses:
        '201':
          description: Item created
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ItemNode'
        '400':
          $ref: '#/components/responses/BadRequest'
        '503':
          $ref: '#/components/responses/ServiceUnavailable'

  /api/v1/itemtree/items/get:
    post:
      tags: [items]
      operationId: getItems
      summary: Bulk get by id list (POST avoids URL-length limits)
      parameters:
        - $ref: '#/components/parameters/XIceUser'
        - $ref: '#/components/parameters/XImpersonatedUser'
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/GetItemsRequest'
      responses:
        '200':
          description: Items found; missing ids are silently omitted
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: '#/components/schemas/ItemNodeWithData'
        '503':
          $ref: '#/components/responses/ServiceUnavailable'

  /api/v1/itemtree/items/{id}:
    delete:
      tags: [items]
      operationId: deleteItem
      summary: Delete item; cascades to all descendants when item is a folder
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: integer
            format: int64
        - $ref: '#/components/parameters/XIceUser'
        - $ref: '#/components/parameters/XImpersonatedUser'
      responses:
        '204':
          description: Deleted
        '404':
          $ref: '#/components/responses/NotFound'
        '503':
          $ref: '#/components/responses/ServiceUnavailable'

  /api/v1/itemtree/items/{id}/move:
    post:
      tags: [items]
      operationId: moveItem
      summary: Move item to a new parent folder
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: integer
            format: int64
        - $ref: '#/components/parameters/XIceUser'
        - $ref: '#/components/parameters/XImpersonatedUser'
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/MoveRequest'
      responses:
        '200':
          description: Item moved
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ItemNode'
        '400':
          $ref: '#/components/responses/BadRequest'
        '404':
          $ref: '#/components/responses/NotFound'
        '503':
          $ref: '#/components/responses/ServiceUnavailable'

  /api/v1/itemtree/items/{id}/rename:
    post:
      tags: [items]
      operationId: renameItem
      summary: Rename item
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: integer
            format: int64
        - $ref: '#/components/parameters/XIceUser'
        - $ref: '#/components/parameters/XImpersonatedUser'
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/RenameRequest'
      responses:
        '200':
          description: Item renamed
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ItemNode'
        '400':
          $ref: '#/components/responses/BadRequest'
        '404':
          $ref: '#/components/responses/NotFound'
        '503':
          $ref: '#/components/responses/ServiceUnavailable'

  /api/v1/itemtree/items/{id}/data:
    put:
      tags: [items]
      operationId: updateItemData
      summary: Full JSON replace for item payload
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: integer
            format: int64
        - $ref: '#/components/parameters/XIceUser'
        - $ref: '#/components/parameters/XImpersonatedUser'
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/UpdateDataRequest'
      responses:
        '200':
          description: Data updated
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ItemNode'
        '400':
          $ref: '#/components/responses/BadRequest'
        '404':
          $ref: '#/components/responses/NotFound'
        '503':
          $ref: '#/components/responses/ServiceUnavailable'

  /api/v1/itemtree/tree:
    get:
      tags: [tree]
      operationId: getTree
      summary: Get trimmed tree view for the resolved user (skeleton + ancestor chain + home-folder children)
      parameters:
        - $ref: '#/components/parameters/XIceUser'
        - $ref: '#/components/parameters/XImpersonatedUser'
      responses:
        '200':
          description: Flat list of ItemNode each with path populated
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: '#/components/schemas/ItemNode'
        '404':
          $ref: '#/components/responses/NotFound'
        '503':
          $ref: '#/components/responses/ServiceUnavailable'

  /api/v1/itemtree/tree/{rootId}/subtree:
    get:
      tags: [tree]
      operationId: getSubtree
      summary: Get subtree rooted at rootId as a flat list with paths
      parameters:
        - name: rootId
          in: path
          required: true
          schema:
            type: integer
            format: int64
        - $ref: '#/components/parameters/XIceUser'
        - $ref: '#/components/parameters/XImpersonatedUser'
      responses:
        '200':
          description: Flat list of ItemNode each with path populated
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: '#/components/schemas/ItemNode'
        '404':
          $ref: '#/components/responses/NotFound'
        '503':
          $ref: '#/components/responses/ServiceUnavailable'

  /api/v1/itemtree/search:
    get:
      tags: [search]
      operationId: search
      summary: Search by id or name; supply exactly one of id or name
      parameters:
        - $ref: '#/components/parameters/XIceUser'
        - $ref: '#/components/parameters/XImpersonatedUser'
        - name: id
          in: query
          required: false
          schema:
            type: integer
            format: int64
        - name: name
          in: query
          required: false
          schema:
            type: string
        - name: limit
          in: query
          required: false
          schema:
            type: integer
            format: int32
      responses:
        '200':
          description: Search results
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: '#/components/schemas/SearchHit'
        '400':
          $ref: '#/components/responses/BadRequest'
        '503':
          $ref: '#/components/responses/ServiceUnavailable'

  /api/v1/itemtree/users/{userName}/home-folder:
    get:
      tags: [users]
      operationId: getHomeFolder
      summary: Resolve home folder for the given user
      parameters:
        - name: userName
          in: path
          required: true
          schema:
            type: string
        - $ref: '#/components/parameters/XIceUser'
        - $ref: '#/components/parameters/XImpersonatedUser'
      responses:
        '200':
          description: Home folder found
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ItemNode'
        '404':
          description: Home folder not found
          content:
            application/problem+json:
              schema:
                $ref: '#/components/schemas/Problem'
        '503':
          $ref: '#/components/responses/ServiceUnavailable'

components:

  parameters:
    XIceUser:
      name: X-Ice-User
      in: header
      required: true
      schema:
        type: string
        minLength: 1
        maxLength: 20

    XImpersonatedUser:
      name: X-Impersonated-User
      in: header
      required: false
      schema:
        type: string
        minLength: 1
        maxLength: 20

  schemas:

    ItemNode:
      type: object
      required: [itemTreeId, parentId, name, type, lastUpdate, lastUpdateUser]
      properties:
        itemTreeId:
          type: integer
          format: int64
        parentId:
          type: integer
          format: int64
          description: "Always populated; 0 for root's parent"
        name:
          type: string
        type:
          type: string
        path:
          type: string
          nullable: true
          description: "Root-anchored slash-separated path, e.g. root/Folder1/IceReport; populated only on /tree and /subtree responses"
        lastUpdate:
          type: string
          format: date-time
        lastUpdateUser:
          type: string

    ItemNodeWithData:
      type: object
      required: [itemTreeId, parentId, name, type, lastUpdate, lastUpdateUser]
      properties:
        itemTreeId:
          type: integer
          format: int64
        parentId:
          type: integer
          format: int64
        name:
          type: string
        type:
          type: string
        lastUpdate:
          type: string
          format: date-time
        lastUpdateUser:
          type: string
        dataJson:
          type: object
          nullable: true
          additionalProperties: true
          description: "Parsed JSON payload; at most one of dataJson/dataXml is populated"
        dataXml:
          type: string
          nullable: true
          description: "Raw XML payload; at most one of dataJson/dataXml is populated"
        children:
          type: array
          description: "Direct children populated only when node is a Folder (one level deep)"
          items:
            $ref: '#/components/schemas/ItemNodeWithData'

    SearchHit:
      type: object
      required: [itemTreeId, name, type]
      properties:
        itemTreeId:
          type: integer
          format: int64
        name:
          type: string
        type:
          type: string

    CreateItemRequest:
      type: object
      required: [parentId, name, type]
      properties:
        parentId:
          type: integer
          format: int64
        name:
          type: string
          minLength: 1
          maxLength: 70
        type:
          type: string
          minLength: 1
          maxLength: 30
        data:
          type: object
          nullable: true
          additionalProperties: true
          description: "Must be null for types-without-data (400 TYPE_CANNOT_HAVE_DATA otherwise)"

    GetItemsRequest:
      type: object
      required: [ids]
      properties:
        ids:
          type: array
          items:
            type: integer
            format: int64

    MoveRequest:
      type: object
      required: [newParentId]
      properties:
        newParentId:
          type: integer
          format: int64

    RenameRequest:
      type: object
      required: [newName]
      properties:
        newName:
          type: string
          minLength: 1
          maxLength: 70

    UpdateDataRequest:
      type: object
      required: [data]
      properties:
        data:
          type: object
          additionalProperties: true

    Problem:
      type: object
      description: RFC 7807 problem detail
      properties:
        type:
          type: string
          format: uri
        title:
          type: string
        status:
          type: integer
        detail:
          type: string
        instance:
          type: string
          format: uri
        errorCode:
          type: string
          description: "Machine-readable code e.g. PARENT_NOT_FOUND, MOVE_INTO_DESCENDANT"
        traceId:
          type: string

  responses:
    BadRequest:
      description: Bad request
      content:
        application/problem+json:
          schema:
            $ref: '#/components/schemas/Problem'
    NotFound:
      description: Not found
      content:
        application/problem+json:
          schema:
            $ref: '#/components/schemas/Problem'
    ServiceUnavailable:
      description: Service unavailable — cache not ready
      content:
        application/problem+json:
          schema:
            $ref: '#/components/schemas/Problem'
```

- [ ] **Step 2: Run generation and verify it succeeds**

```
./gradlew openApiGenerate
```

Expected: BUILD SUCCESSFUL. Inspect generated files:

```
find build/generated/openapi/src/main/java -name "*.java" | sort
```

Expected API interfaces:
```
.../generated/api/ItemsApi.java
.../generated/api/SearchApi.java
.../generated/api/SpringDocConfiguration.java
.../generated/api/TreeApi.java
.../generated/api/UsersApi.java
```

Expected model DTOs:
```
.../generated/model/CreateItemRequest.java
.../generated/model/GetItemsRequest.java
.../generated/model/ItemNode.java
.../generated/model/ItemNodeWithData.java
.../generated/model/MoveRequest.java
.../generated/model/Problem.java
.../generated/model/RenameRequest.java
.../generated/model/SearchHit.java
.../generated/model/UpdateDataRequest.java
```

If generation fails with a parse error, fix the YAML (check indentation, `$ref` spelling, and that all referenced schemas are defined in `components/schemas`).

- [ ] **Step 3: Note the exact method signatures before writing controllers**

Run:
```
grep -n "default ResponseEntity\|ResponseEntity<" \
  build/generated/openapi/src/main/java/com/myxcomp/ice/xtree/generated/api/ItemsApi.java
```

The controller step below uses signatures derived from the YAML above and openapi-generator 7.10.0 conventions. If any `@Override` causes a "method does not override" compile error, open the failing `*Api.java`, copy the exact signature, and update the controller.

- [ ] **Step 4: Commit the YAML**

```bash
git add src/main/resources/openapi/itemtree-api.yaml
git commit -m "feat(phase1): author OpenAPI 3.0.3 spec for all 10 ITEMTREE endpoints"
```

---

## Task 4: Create stub controllers

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/api/controller/ItemController.java`
- Create: `src/main/java/com/myxcomp/ice/xtree/api/controller/TreeController.java`
- Create: `src/main/java/com/myxcomp/ice/xtree/api/controller/SearchController.java`
- Create: `src/main/java/com/myxcomp/ice/xtree/api/controller/UserController.java`

Signatures below are derived from the YAML in Task 3 and openapi-generator 7.10.0 Spring generator conventions. Header `X-Ice-User` → Java param `xIceUser`; `X-Impersonated-User` → `xImpersonatedUser`. If a signature doesn't compile, open the generated `*Api.java` and match it exactly.

- [ ] **Step 1: Create `ItemController.java`**

```java
package com.myxcomp.ice.xtree.api.controller;

import com.myxcomp.ice.xtree.generated.api.ItemsApi;
import com.myxcomp.ice.xtree.generated.model.CreateItemRequest;
import com.myxcomp.ice.xtree.generated.model.GetItemsRequest;
import com.myxcomp.ice.xtree.generated.model.ItemNode;
import com.myxcomp.ice.xtree.generated.model.ItemNodeWithData;
import com.myxcomp.ice.xtree.generated.model.MoveRequest;
import com.myxcomp.ice.xtree.generated.model.RenameRequest;
import com.myxcomp.ice.xtree.generated.model.UpdateDataRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class ItemController implements ItemsApi {

    @Override
    public ResponseEntity<ItemNode> createItem(
            String xIceUser, String xImpersonatedUser,
            CreateItemRequest createItemRequest) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ResponseEntity<Void> deleteItem(
            Long id, String xIceUser, String xImpersonatedUser) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ResponseEntity<List<ItemNodeWithData>> getItems(
            String xIceUser, String xImpersonatedUser,
            GetItemsRequest getItemsRequest) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ResponseEntity<ItemNode> moveItem(
            Long id, String xIceUser, String xImpersonatedUser,
            MoveRequest moveRequest) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ResponseEntity<ItemNode> renameItem(
            Long id, String xIceUser, String xImpersonatedUser,
            RenameRequest renameRequest) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ResponseEntity<ItemNode> updateItemData(
            Long id, String xIceUser, String xImpersonatedUser,
            UpdateDataRequest updateDataRequest) {
        throw new UnsupportedOperationException();
    }
}
```

- [ ] **Step 2: Create `TreeController.java`**

```java
package com.myxcomp.ice.xtree.api.controller;

import com.myxcomp.ice.xtree.generated.api.TreeApi;
import com.myxcomp.ice.xtree.generated.model.ItemNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class TreeController implements TreeApi {

    @Override
    public ResponseEntity<List<ItemNode>> getTree(
            String xIceUser, String xImpersonatedUser) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ResponseEntity<List<ItemNode>> getSubtree(
            Long rootId, String xIceUser, String xImpersonatedUser) {
        throw new UnsupportedOperationException();
    }
}
```

- [ ] **Step 3: Create `SearchController.java`**

```java
package com.myxcomp.ice.xtree.api.controller;

import com.myxcomp.ice.xtree.generated.api.SearchApi;
import com.myxcomp.ice.xtree.generated.model.SearchHit;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class SearchController implements SearchApi {

    @Override
    public ResponseEntity<List<SearchHit>> search(
            String xIceUser, String xImpersonatedUser,
            Long id, String name, Integer limit) {
        throw new UnsupportedOperationException();
    }
}
```

- [ ] **Step 4: Create `UserController.java`**

```java
package com.myxcomp.ice.xtree.api.controller;

import com.myxcomp.ice.xtree.generated.api.UsersApi;
import com.myxcomp.ice.xtree.generated.model.ItemNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserController implements UsersApi {

    @Override
    public ResponseEntity<ItemNode> getHomeFolder(
            String userName, String xIceUser, String xImpersonatedUser) {
        throw new UnsupportedOperationException();
    }
}
```

- [ ] **Step 5: Verify compilation**

```
./gradlew compileJava
```

Expected: BUILD SUCCESSFUL. If you get `"method does not override or implement a method from a supertype"` on any `@Override`, open the corresponding generated `*Api.java` and match the method name, return type, and parameter types exactly. The parameter names in the generated interface are `xIceUser` and `xImpersonatedUser` (derived from the header names in the YAML) — if different, use whatever the generated interface declares.

- [ ] **Step 6: Commit the controllers**

```bash
git add src/main/java/com/myxcomp/ice/xtree/api/controller/
git commit -m "feat(phase1): add stub controllers implementing generated *Api interfaces"
```

---

## Task 5: Run the full build and verify the acceptance test passes

- [ ] **Step 1: Run the full build**

```
./gradlew build
```

Expected: BUILD SUCCESSFUL. All tests PASS — `ItemTreeApplicationTests` (3 tests) and `ApiContractTest` (2 tests).

If `ApiContractTest.apiDocsReturnsOkWithCorrectTitle` fails with `"OpenAPI definition"` instead of `"ItemTree API"`, the `SpringDocConfiguration` generated bean was not picked up. Check that the generated file exists:

```
cat build/generated/openapi/src/main/java/com/myxcomp/ice/xtree/generated/api/SpringDocConfiguration.java
```

If it sets a different title than `"ItemTree API"`, verify the `info.title` in your YAML matches exactly. If the file wasn't generated at all (generator version behaviour difference), add a `@Bean` manually in `src/main/java/com/myxcomp/ice/xtree/config/OpenApiConfig.java`:

```java
package com.myxcomp.ice.xtree.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI itemTreeOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("ItemTree API")
                        .description("REST service fronting the ITEMTREE Oracle table.")
                        .version("1.0.0"));
    }
}
```

- [ ] **Step 2: Smoke-test the running app**

```
./gradlew bootRun &
sleep 10
curl -s http://localhost:8080/v3/api-docs | python3 -m json.tool | grep -E '"title"|"operationId"'
kill %1
```

Expected lines in the output:
```
"title": "ItemTree API",
"operationId": "createItem",
"operationId": "getItems",
"operationId": "deleteItem",
"operationId": "moveItem",
"operationId": "renameItem",
"operationId": "updateItemData",
"operationId": "getTree",
"operationId": "getSubtree",
"operationId": "search",
"operationId": "getHomeFolder",
```

- [ ] **Step 3: Update IMPLEMENTATION_NOTES.md to mark Phase 1 complete**

In `IMPLEMENTATION_NOTES.md`, change the Phase 1 heading line from:

```
## Phase 1 — API contract ⬅ NEXT
```

to:

```
## Phase 1 — API contract ✅ COMPLETE (2026-05-14)
```

And update Phase 2 heading from:

```
## Phase 2 — Domain types and common primitives
```

to:

```
## Phase 2 — Domain types and common primitives ⬅ NEXT
```

- [ ] **Step 4: Final commit**

```bash
git add IMPLEMENTATION_NOTES.md
git commit -m "docs: mark Phase 1 complete, advance Phase 2 as next"
git tag phase-1-api-contract
```
