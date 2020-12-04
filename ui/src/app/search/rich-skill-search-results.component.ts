import {Component, OnInit} from "@angular/core";
import {SearchService} from "./search.service";
import {RichSkillService} from "../richskill/service/rich-skill.service";
import {ApiSearch, PaginatedSkills} from "../richskill/service/rich-skill-search.service";
import {ActivatedRoute, NavigationStart, Router} from "@angular/router";
import {ToastService} from "../toast/toast.service";
import {SkillsListComponent} from "../richskill/list/skills-list.component";
import {ApiSkillSummary} from "../richskill/ApiSkillSummary";
import {determineFilters} from "../PublishStatus";
import {TableActionDefinition} from "../table/skills-library-table/has-action-definitions";
import {ExtrasSelectedSkillsState} from "../collection/add-skills-collection.component";


@Component({
  selector: "app-rich-skill-search-results",
  templateUrl: "../richskill/list/skills-list.component.html"
})
export class RichSkillSearchResultsComponent extends SkillsListComponent implements OnInit {

  apiSearch: ApiSearch | undefined

  title = "Search Results"

  showSearchEmptyMessage = true
  private multiplePagesSelected: boolean = false

  constructor(protected router: Router,
              protected richSkillService: RichSkillService,
              protected toastService: ToastService,
              protected searchService: SearchService,
              protected route: ActivatedRoute,
) {
    super(router, richSkillService, toastService)
    this.searchService.searchQuery$.subscribe(apiSearch => this.handleNewSearch(apiSearch) )
    router.events.subscribe(event => {
      if (event instanceof NavigationStart) {
        this.searchService.clearSearch()
      }
    })
  }

  ngOnInit(): void {
    if (this.searchService.latestSearch !== undefined) {
      this.handleNewSearch(this.searchService.latestSearch)
    } else {
      this.route.queryParams.subscribe(params => {
        const query = params.q
        if (query && query.length > 0) {
          this.handleNewSearch(new ApiSearch({query}))
        }
      })
    }
  }

  private handleNewSearch(apiSearch?: ApiSearch): void {
    this.apiSearch = apiSearch
    if (this.apiSearch?.query !== undefined) {
      this.matchingQuery = [this.apiSearch.query]
    } else if (this.apiSearch?.advanced !== undefined) {
      this.matchingQuery = Object.getOwnPropertyNames(this.apiSearch?.advanced).map((k) => {
        const a: any = this.apiSearch?.advanced
        return a !== undefined ? a[k] : undefined
      }).filter(x => x !== undefined)
    }
    this.loadNextPage()
  }

  loadNextPage(): void {
    if (this.selectedFilters.size < 1) {
      this.setResults(new PaginatedSkills([], 0))
      return
    }

    if (this.apiSearch !== undefined) {
      this.resultsLoaded = this.richSkillService.searchSkills(this.apiSearch, this.size, this.from, determineFilters(this.selectedFilters), this.columnSort)
      this.resultsLoaded.subscribe(results => this.setResults(results))
    }
  }

  getApiSearch(skill?: ApiSkillSummary): ApiSearch | undefined {
    return (this.multiplePagesSelected) ? this.apiSearch : super.getApiSearch(skill)
  }

  handleSelectAll(selectAllChecked: boolean): void {
    this.multiplePagesSelected = this.totalPageCount > 1
  }

  getSelectAllCount(): number {
    return this.totalCount
  }

  handleClickAddCollection(action: TableActionDefinition, skill?: ApiSkillSummary): boolean {
    this.router.navigate(["/collections/add-skills"], {
      state: {
        selectedSkills: this.getSelectedSkills(skill),
        totalCount: this.totalCount,
        search: this.apiSearch
      } as ExtrasSelectedSkillsState
    })
    return false
  }
}