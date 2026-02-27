# C4 / LikeC4 DSL Style Guidelines

Conventions for all `.c4` files in `c4-spec/`.

## File Organization

- `_spec.c4` — element type and style definitions only; no model elements
- `model.c4` — all model elements and relationships; one `model { }` block
- `deployment.c4` — deployment topology; one `deployment { }` block
- `views.c4` — all views; one `views { }` block

## Naming

- `snake_case` for all element identifiers (e.g., `mobile_app`, `keycloak_postgres_db`)
- Deployment pod names must match or clearly correspond to the model element they instantiate

## Relationships

- Use `->` with a short inline label
- Multi-line descriptions use triple-quoted block strings:
  ```
  -> other_element "Short label" {
      description '''
      Longer explanation.
      '''
  }
  ```

## Views

- `include *` for overview views
- `include element._` to include direct children
- Dynamic views must list all involved elements in the closing `include`
- Every non-index view needs an explicit `title`

## Element Types (from `_spec.c4`)

| Type | Shape | Use for |
|---|---|---|
| `actor` | person | Human users |
| `backend` | default | Server-side services |
| `system` | default | External systems |
| `component` | component | Internal sub-components |
| `kc_extension` | component | Keycloak extensions |
| `kc_idp` | component | Keycloak IDP configurations |
| `webapp` | browser | Web frontends |
| `mobileapp` | mobile | Mobile clients |
| `database` | storage | Databases / persistent stores |

Deployment node types: `pod`, `kubernetes_cluster`

## Commands

```bash
npx likec4 serve c4-spec/       # preview in browser
npx likec4 validate c4-spec/    # lint
npx likec4 export png c4-spec/ --output ./diagrams/
```
