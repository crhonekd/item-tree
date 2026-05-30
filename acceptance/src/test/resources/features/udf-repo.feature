Feature: UDFRepo per-user singleton

  A UDFRepo lives directly in the user's home folder, is named after the user,
  and is unique per user. It can never be duplicated, renamed, moved, or deleted —
  only its data may be updated.

  Scenario: a user's UDFRepo cannot be duplicated, renamed, moved, or deleted
    Given my UDFRepo exists
    When I create another UDFRepo
    Then the response status is 400
    And the error code is "UDF_REPO_ALREADY_EXISTS"
    When I try to delete my UDFRepo
    Then the response status is 400
    And the error code is "UDF_REPO_PROTECTED"
    When I try to rename my UDFRepo to "hacked"
    Then the response status is 400
    And the error code is "UDF_REPO_PROTECTED"
    When I try to move my UDFRepo into the sandbox
    Then the response status is 400
    And the error code is "UDF_REPO_PROTECTED"
