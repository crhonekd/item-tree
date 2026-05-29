Feature: Harness smoke

  Scenario: the target instance is reachable and ready
    When I get the tree
    Then the response status is 200
