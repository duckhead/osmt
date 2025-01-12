import {HttpClientTestingModule} from "@angular/common/http/testing"
import {Component, Type} from "@angular/core"
import {async, ComponentFixture, TestBed} from "@angular/core/testing"
import {FormsModule, ReactiveFormsModule} from "@angular/forms"
import {ActivatedRoute, Router} from "@angular/router"
import {RouterTestingModule} from "@angular/router/testing"
import {AppConfig} from "src/app/app.config"
import {EnvironmentService} from "src/app/core/environment.service"
import {ApiSortOrder} from "../../../richskill/ApiSkill"
import {ActivatedRouteStubSpec} from "test/util/activated-route-stub.spec"
import {ISelectOption, SelectLabelComponent} from "./select-label.component"

@Component({
  template: ""
})
export abstract class TestHostComponent extends SelectLabelComponent { }

let activatedRoute: ActivatedRouteStubSpec
let fixture: ComponentFixture<TestHostComponent>
let component: TestHostComponent

function createComponent(T: Type<TestHostComponent>): void {
  fixture = TestBed.createComponent(T)
  component = fixture.componentInstance
  fixture.detectChanges()
  fixture.whenStable().then(() => fixture.detectChanges())
}

describe("SelectLabelComponent", () => {

  beforeEach(() => {
    activatedRoute = new ActivatedRouteStubSpec()
  })

  beforeEach(async(() => {
    const routerSpy = ActivatedRouteStubSpec.createRouterSpy()

    TestBed.configureTestingModule({
      declarations: [
        SelectLabelComponent,
        TestHostComponent
      ],
      imports: [
        FormsModule,  // Required for ([ngModel])
        ReactiveFormsModule,
        RouterTestingModule,  // Required for routerLink
        HttpClientTestingModule,  // Needed to avoid the toolName race condition below
      ],
      providers: [
        AppConfig,  // Needed to avoid the toolName race condition below
        EnvironmentService,  // Needed to avoid the toolName race condition below
        { provide: ActivatedRoute, useValue: activatedRoute },
        { provide: Router, useValue: routerSpy },
      ]
    }).compileComponents()

    const appConfig = TestBed.inject(AppConfig)
    AppConfig.settings = appConfig.defaultConfig()  // This avoids the race condition on reading the config's whitelabel.toolName

    activatedRoute.setParams({ userId: 126 })

    // @ts-ignore
    createComponent(TestHostComponent)
  }))

  it("should be created", () => {
    expect(component).toBeTruthy()
  })

  it("isOptionSelected should return correct value", () => {
    const option1 = { label: "option1", value: {} } as ISelectOption<any>
    const option2 = { label: "option2", value: {} } as ISelectOption<any>
    component.selected = option1

    expect(component.isOptionSelected(option1)).toEqual(true)
    expect(component.isOptionSelected(option2)).toEqual(false)
  })

  it("handleOptionSelected should succeed", () => {
    const option1 = { label: "option1", value: {} } as ISelectOption<any>
    component.selected = option1

    component.onSelection.subscribe((selection) => {
      expect(selection).toEqual(option1)
    })

    component.handleOptionSelected()
  })
})
