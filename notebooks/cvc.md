# ClinGen Curation of ClinVar 

For some time groups within ClinGen have been reviewing sequence variants in ClinVar. They have been prioritizing variants that have conflicting calls of pathogenicity, and are particularly looking to resolve the conflict at the aggregate level by flagging calls that are plainly incorrect or well out-of-date.

The process established works as follows:

* A conflict report is generated over conflicting variants in ClinVar
* ClinGen groups review unresolved conflicts, looking especially for:
  * Outlier claims with little evidence
  * Claims with little evidence generally
  * Classification that conflicts with expert classification without sufficient supporting evidence
  * Claim on irrelevant condition for the variant
* ClinGen experts submit candidate submissions for flagging to ClinVar
* ClinVar contacts labs with candidate submissions for flagging. The lab may elect to:
  * Update the classification to reflect current evidence. No flag will be recorded on the submission.
  * Maintain the existing classification, adding a justification for its current relevance. No flag will be recorded for the submission.
  * Ignore it. The variant will be flagged in ClinVar and will not contribute to the aggregate classification for the variant.
  
  
##### Examples

[SELENON:c.300del](https://www.ncbi.nlm.nih.gov/clinvar/variation/662908/)

[partial list of submissions with flagged records](https://www.ncbi.nlm.nih.gov/clinvar?term=%22no%20classifications%20from%20unflagged%20records%22%5BReview%20status%5D)

##### References

[ClinGen Curation of ClinVar](https://www.clinicalgenome.org/curation-activities/clingen-curation-of-clinvar/)

[Curation in ClinVar](https://www.ncbi.nlm.nih.gov/clinvar/docs/curation/#flagged)

[CVC SOP](https://docs.google.com/document/d/1HQ8jngoMPaI-1IgXOGC_gm3SnNW4toU_)

#### Template letter to submitters

Thank you for your past contributions to ClinVar. As you may know, the ClinVar team works closely with ClinGen (https://www.clinicalgenome.org/). One of our collaborations involves ClinGen curation of variants in ClinVar that have a conflict in the classification. In some cases, the conflict is due to a submitted record (SCV) that has an incorrect, outdated, or unnecessarily conflicting classification. Please see the ClinGen website page that describes this project here: https://www.clinicalgenome.org/clingen-curation-clinvar.

The ClinGen curation team has identified one or more SCVs submitted by your organization SUBMITTER (Organization ID) that appear to cause a conflict for one of these reasons. The attached file "FILE" includes the SCV accession number, the curation date, the reason it appears to cause a conflict, and in some cases a free text note explaining the issue further.

You have the opportunity to review these records to determine if either the classification, condition and/or evidence summary should be updated to address the concern raised by ClinGen. If so, you can use our usual submission processes to make the updates (https://www.ncbi.nlm.nih.gov/clinvar/docs/submit/#update).

We ask you to make any necessary updates within 60 days of the date this email was sent. If no update is made in that time period, the records listed will be flagged in ClinVar. The flag will indicate that the SCV was curated by ClinGen, and will include the reason and note provided by them. The flag will prevent the SCV from contributing to the overall classification for the variant, with the goal of resolving conflicting classifications when the ClinGen curation team feels there is a clear rationale for doing so. If you update an SCV after the flag has already been set, the flag will be removed from the SCV record at that time.

Updating your records is not required, but it is preferable to flagging them. So please contact us at clinvar@ncbi.nlm.nih.gov if you have any questions about ClinGen's curation or about updating your records.

Sincerely,
The ClinVar Team

## CNV Pilot

The curation of ClinVar for sequence variants began with a pilot and a community review process; we are looking to do the same for copy number variation. There are many aspects of this that will we quite different for CNVs relative to sequence variants:

* Assessed conflicts of interpretation for sequence variants are made for *identical* variants. Submitted CNVs are seldom identical, but are often similar enough that they have conflicting interpretations.
  * A B/LB/VUS call on a copy number loss variant that completely overlaps a P/LP call.
* Sequence variants are typically submitted relative to a specific condition. CNVs are usually submitted relative to any/all highly penetrant genetic disease, with specific observed phenotypes noted separately.
* The variant may be associated with a specific mechanism or mode of inheritance given the association with the disease. There is less clarity about how to report this for CNVs.

The goal is not to work though every difficulty prior to beginning the process, but to start with clear candidates for flagging; expanding the scope as we go.

For the initial set of candidates, we are looking at **B/LB/VUS calls** on **copy number loss variants** that completely overlap genes and regions identified as **HI 3 (sufficient)** in the **ClinGen Dosage Map**. By the standards for sequence variants, this would be similar to a conflict against an expert curation of a variant.

### Pilot Variant Calls

[Candidates reviewed by ClinGen](https://docs.google.com/spreadsheets/d/1pUVm-DWGE72VXsh-0MDKooyWQWDOwIT-bsBLBdRYA0s/edit?gid=235601266#gid=235601266)

### The Ask

* Please review the list above.
* For variant calls made by your lab, please consider revising your submission to ClinVar if you agree that the variant should be classified differently.
  * If you do, please let us know.
  * If you disagree that these calls are incorrect, please also let us know.
* If you would appreciate having additional context for evaluating these variants, please let us know and I can add additional info.
* Please offer us feedback, support, or criticism on this process as it relates to CNVs. This feedback will give us a starting point for discussion with ClinVar about expanding the current CvC process to include CNVs.

Tristan Nelson: thnelson@geisinger.edu


### Awknowledgements

Tracy Brandt (Geisinger), Tasha Strande (Mayo) for discussion, concept, and review of variants.
