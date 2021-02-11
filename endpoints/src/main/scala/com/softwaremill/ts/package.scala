package com.softwaremill

import sttp.tapir.Endpoint

package object ts {
  type Identity[X] = X
  type AnyEndpoint = Endpoint[_, _, _, _]
}
