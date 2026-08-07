

Schema: public

  View: rows_from_explicit
    RelationColumns :\s*
     renamed_id - integer
     renamed_text - text
     second_amount - numeric

  View: rows_from_unaliased
    RelationColumns :\s*
     first_id - integer
     first_text - text
     second_amount - numeric
     ordinality - bigint

  View: rows_from_unaliased_qualified
    RelationColumns :\s*
     first_value - integer
     second_value - numeric
     row_number - bigint
