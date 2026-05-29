Feature: Read endpoints

  Scenario: created item appears in the sandbox subtree with root-anchored paths
    When I create a Report named "acc-read-1"
    Then the response status is 201
    When I get the full subtree of the sandbox
    Then the response status is 200
    And the subtree includes an item named "acc-read-1"
    And every item path starts with "/"
    When I delete it
    Then the response status is 204

  Scenario: getItems returns the created item with a root-anchored path
    When I create a View named "acc-read-2"
    Then the response status is 201
    When I fetch it
    Then the response status is 200
    And the item's path starts with "/"
    When I delete it
    Then the response status is 204

  Scenario: the immediate subtree of the sandbox is reachable
    When I get the subtree of the sandbox
    Then the response status is 200

  Scenario: the home folder resolves for the configured user
    When I resolve the home folder for "crhonekd"
    Then the response status is 200
    And the item's type is "Folder"

  Scenario: the tree skeleton is reachable
    When I get the tree
    Then the response status is 200
