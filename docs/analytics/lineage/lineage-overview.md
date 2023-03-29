# Lineage

## Background

Exposing and understanding data lineage can be important for several reasons: 
- Regulatory or compliance requirements, to show traceability of where data is originating from 
- Operational visibility, to establish the blast radius of incidents or quality issues on source datasets
- Change management activities, that require identifying of downstream datasets that may be impacted by a proposed change

## Legend Lineage Features

The same strong metamodel that data model owners/providers define to create queryable/executable data models against 
a given store/source can be equally be leveraged to provide inferred/automated lineage traceability.

Lineage can have several forms
* Dataset level lineage
* Attribute level lineage

For example given a `Service`, `Mapping` and `Runtime`, we are able to use the functions in the 
`meta::analytics::lineage` we can compute various analytics such as:

1. Function Analytics
2. Report Lineage
