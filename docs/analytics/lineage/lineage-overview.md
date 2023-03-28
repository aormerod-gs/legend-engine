# Lineage

## Background

Exposing and understanding data lineage can be important for several reasons: 
- Regulatory or compliance requirements
- Operational visibility, to establish the blast radius of incidents / quality issues on source datasets
- Change management activities, that require identifying downstream datasets that may be impacted by a given change

## Legend Lineage Features

The same strong metamodel that data model owners / providers define to create queryable / executable data models
can be equally be leveraged to provide inferred / automated lineage traceability.

For example given a `Service`, `Mapping` and `Runtime`, we are able to use the functions in the 
`meta::analytics::lineage` we can compute various analytics such as:

1. Function Analytics
2. Report Lineage
