# ---------------------------------------------------------------------------
# Flow Bindings
# ---------------------------------------------------------------------------
# This file contains realm-level flow binding configurations.
# Client-level flow bindings are managed directly in realm.tf with explicit
# depends_on clauses to control the dependency order.
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# Realm Browser Flow Binding
# ---------------------------------------------------------------------------
# Set the step-up-browser-flow as the default browser flow for the realm.
# This must be done after both the realm and the flow are created.
# ---------------------------------------------------------------------------
resource "keycloak_authentication_bindings" "realm_browser_flow" {
  realm_id     = keycloak_realm.auth_sandbox.id
  browser_flow = keycloak_authentication_flow.step_up_browser_flow.alias

  depends_on = [
    keycloak_realm.auth_sandbox,
    keycloak_authentication_flow.step_up_browser_flow,
    keycloak_authentication_execution.step_up_browser_cookie,
    keycloak_authentication_subflow.step_up_subflow_execution,
    keycloak_authentication_subflow.level1_subflow,
    keycloak_authentication_subflow.level2_subflow,
    keycloak_authentication_execution.level2_otp,
  ]
}
