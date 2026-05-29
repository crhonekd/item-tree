Feature: Error handling

  Scenario: renaming a non-existent item returns 404
    When I rename item 999999999 to "nope"
    Then the response status is 404
    And the error code is "ITEM_NOT_FOUND"

  Scenario: creating outside the user's home folder is forbidden
    When I create a Report named "acc-illegal" in folder 1
    Then the response status is 403
    And the error code is "NOT_IN_USER_FOLDER"

  Scenario: a type that cannot hold data rejects data
    When I create a Shortcut named "acc-bad-data" with data:
      """
      { "x": 1 }
      """
    Then the response status is 400
    And the error code is "TYPE_CANNOT_HAVE_DATA"

  Scenario: an empty name is rejected
    When I create a Report named ""
    Then the response status is 400
