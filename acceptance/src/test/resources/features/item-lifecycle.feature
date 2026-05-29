Feature: Item mutation lifecycle

  Background:
    Given a folder named "dest"
    And a folder named "copies"

  Scenario Outline: full lifecycle of a <type>
    When I create a <type> named "<name>"
    Then the response status is 201
    When I fetch it
    Then the item's type is "<type>"
    When I rename it to "<name>-renamed"
    And I fetch it
    Then the item's name is "<name>-renamed"
    When I move it into "dest"
    Then the response status is 200
    When I fetch it
    Then the item's parent is "dest"
    When I copy it into "copies"
    Then the response status is 201
    When I delete it
    Then the response status is 204
    And it no longer exists

    Examples:
      | type     | name         |
      | Report   | acc-report   |
      | Filter   | acc-filter   |
      | View     | acc-view     |
      | Shortcut | acc-shortcut |

  Scenario: update data of a data-bearing item
    When I create a Report named "acc-data"
    Then the response status is 201
    When I replace its data with:
      """
      { "name": "updated", "n": 99 }
      """
    Then the response status is 200
    When I delete it
    Then the response status is 204
    And it no longer exists
