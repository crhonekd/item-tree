Feature: Search

  Scenario: find a created item by name with its ancestor chain
    When I create a Report named "acc-unique-search-name"
    Then the response status is 201
    When I search for "acc-unique-search-name"
    Then the response status is 200
    And the results include "acc-unique-search-name"
    And the hit "acc-unique-search-name" has a path
    And the hit "acc-unique-search-name" has a non-empty ancestor chain
    When I delete it
    Then the response status is 204

  Scenario: find a created item by numeric id
    When I create a Filter named "acc-search-by-id"
    Then the response status is 201
    When I search for it by id
    Then the response status is 200
    And the results include "acc-search-by-id"
    When I delete it
    Then the response status is 204
