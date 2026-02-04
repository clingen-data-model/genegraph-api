# Projects

## Erin Query

AD Definitive/Anything w Dosage info

what is the dosage haplo score?

variants in the gene curation are predicted/proven null

ratio of null/other

## Gene Validity Versioning -> Website

This is dependent on populating the the "All Curation Events" topic with data in the format agreed to with Phil.

#### Current Activity

Had a productive meeting with Phil on **Dec 11**; at this point we appear to have a complete specification for sharing data to "All Curation Events"

#### Next Step

Produce transform based on discussion for curation changes from last meeting. Estimated **Jan 8** Will present data on our next Genegraph/Website call at this time.

#### Completion

If the sample data is acceptable, I can populate the "All Curation Events" topic with data from Genegraph shortly afterward, otherwise will plan followup discussion.


## Gene Validity Full SEPIO/GA4GH Data

This dataset has been shared with groups that have specifically requested the data. So far this includes: **UNC**, for incorporation into Phenopackets and NCAS Translator, **Baylor** for incorporation into Linked Data Hub, **Broad** for submission to GenCC. Additionally **G2P** has expressed interest in this data. There is a draft version of detailed documentation, as well as downloadable snapshots being produced hourly.

#### Current Activity

I'm currently trying to align the data with the relevant GA4GH standards as much as possible before a final release. Baylor is also intending to align more of their products with the current GA4GH standards; this is part of a larger effort to Data models are difficult to change after release; I'd like to get as close to the target model as possible. Met with Matt Brush, our partner at Monarch/GA4GH for this last on **Dec 12**. Am concurrently trying to align with Larry on the data to be shared from the VCI using the ACMG v4 standards to achieve consistency across ClinGen.

#### Next Step

I don't think it's tenable to wait for perfect alignment before publicly announcing the availability of the data. I'd like to complete one pass at the data model, wrap up the last of the documentation on it and offer the data with the caveat that elements of the data model are under active development, especially when

Will create a new version of the downloadable data and share with our current partners.

#### Completion

A publicly announced data release will be a significant milestone. I expect to iterate on this for some time after, especially in expanding the amount of detail available for experimental evidence types.

## CNV pathogenicity computational predictor assessment

The CNV guidelines committee is looking at the value of computational predictors in assessing CNV pathogenicity. Historically, scores had been used from DECIPHER, however these scores are outdated and deprecated. We are looking at replacing previous approaches with a new algorithm, GeneBayes. We are looking to assess this algorithm by comparing the score distribution from controls vs cases. We are evaluating the use of gnomAD SV as a control set. For the case set, we intend to combine CNVs pulled from ClinVar with inheritance information and combine it with cases offered from our group of participating clinical labs.

### Next Step

#### Evaluate accessibility of data from sources

Download and incorporate data from gnomadSV. Evaluate whether the full SV data set is required, or if the CNV dataset is sufficient. Planned discussion on **Feb 3**

gnomAD data appears to be accessible and useful. Choice between gnomAD CNV and gnomAD SV. These are very different datasets, gnomAD CNV seems like the more relevant one.

#### Implement scoring based on GeneBayes

Also find GeneBayes scores for genes. If these can't be found or easily computed, reach out through Christa to PI.

Appears to be in supplemental table 1

#### Write code to compute gene overlap based on exonic involement

Erin's slides include detail on how much genetic disruption is required. We already have exons loaded into Genegraph; need to calculate genetic involvement of CNVs next

#### UI Design

Potential to engage Seth as we get closer to a display.

### References

[Erin's DECIPHER DS slides](https://docs.google.com/presentation/d/1OPUz_xHb2pMHDMjT4HMywOQd3Prux-gpwEVtoNDeLX4/edit?slide=id.p#slide=id.p)

[GeneBayes paper](https://pubmed.ncbi.nlm.nih.gov/38977852/)

[GeneBayes GitHub](https://github.com/tkzeng/GeneBayes)

[ACMG Standards drive](https://drive.google.com/drive/u/0/folders/1H1SKzvN3Otn9AircxgU9Aq6sWu_Qr7q-)
 Contains GeneBayes Presentation.
 
[gnomAD SV](https://gnomad.broadinstitute.org/news/2023-11-v4-copy-number-variants/)

## ClinVar

Based on the last CNV Curation Standards call the current focus is on collecting the subset of variants with inheritance information.

For the curation of conflicts in ClinVar, I have in place queries and a prototype data display that can present ClinVar variation with conflicting interpretations alongside relevant gene and region level annotation, including the ClinGen Dosage Map, Gene Validity Classifications, and GenCC. Using a prototype we classified variants that had conflicts against genes and regions in the dosage map, and found that having all the relevant annotation ready-to-hand made the process of classification relatively quick.

We may not be able to submit many of the conflicts beyond those with the dosage map to ClinVar. Many have to do with issues that are unresolved within the CNV Classification Standards group (the treatment of variants associated with recessive conditions; whether a variant submitted to a database should be classified relative to a single condition or all highly penetrant disease).

### Next Steps

#### Report generation

The ACMG/AMP Standards Group has asked for data on which CNVs in ClinVar have inheritance information. The ClinVar interface makes some of these queries [possible](https://www.ncbi.nlm.nih.gov/clinvar/?term=((((%22biparental%22%5BOrigin%5D+OR+%22de+novo%22%5BOrigin%5D+OR+%22inherited%22%5BOrigin%5D+OR+%22maternal%22%5BOrigin%5D+OR+%22paternal%22%5BOrigin%5D+OR+%22uniparental%22%5BOrigin%5D)))+AND+((%22copy+number+gain%22%5BType+of+variation%5D+OR+%22copy+number+loss%22%5BType+of+variation%5D+OR+%22deletion%22%5BType+of+variation%5D+OR+%22duplication%22%5BType+of+variation%5D)))), but can't yet combine them with some of the other data sets out there or compute internal conflicts based on similar (but not identical) variants.

Will expand the report generation capability to include queries like those above, allowing them to be combined with the datasets incorporated into Genegraph.

#### ClinVar Curation

Meeting planned to discuss the submission of uncontroversial conflicts against the Dosage Map to ClinVar with the CNV Classification Standards group on **Jan 21**--this group has representation with most of the affected labs. 

Have been setting up a system for identifying conflicts across the rest of the CNVs in ClinVar. These may or may not be candidates for flagging right away, but characterizing discrepancies in interpretation will hopefully be useful for the standards group.

## Recuration Automation

A major expansion of this project to follow the above. The goal is to build infrastructure that can identify candidate papers for recurating existing GV classifications; prioritize them, and mark their utility. When enough potential new evidence appears, this should trigger a reclassification.

## User-Guided Gene Validity querying

The Gene Validity curation team has asked for various queries over the Gene Validity data over the years. I have been able to provide this by running queries over the data in the Genegraph database, then providing the results in a spreadsheet. By leveraging some of the same techniques used for querying the ClinVar data, I can make this dataset available for user-directed queries.

## Common Infrastructure

### Authentication

Needed for ClinVar Curation, helpful for allowing curators access to Gene Validity queries; data prior to 

### Text Indexes

Useful for making both ClinVar and Gene Validity data easier to search. May be useable via API from ClinGen Website and other services.

Vector-based text indexes are expected to provide a more complete, semantic search and provide the basis for similarity comparison of papers in recuration automation.

### LLM/SLM applications

Classifier for papers for recuration

Text to query generation for searching
