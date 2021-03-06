#
# Copyright (C) 2011, 2018 UNINETT AS
#
# This file is part of Network Administration Visualized (NAV).
#
# NAV is free software: you can redistribute it and/or modify it under
# the terms of the GNU General Public License version 2 as published by
# the Free Software Foundation.
#
# This program is distributed in the hope that it will be useful, but WITHOUT
# ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
# FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
# details.  You should have received a copy of the GNU General Public License
# along with NAV. If not, see <http://www.gnu.org/licenses/>.
#
"""Django form field types for NAV"""

from __future__ import unicode_literals, absolute_import

import json

from django import forms
from django.forms import Field, Textarea
from django.utils import six
from django.core.exceptions import ValidationError

from nav.util import is_valid_cidr
from nav.django import validators, widgets


class CIDRField(forms.CharField):
    """CIDR address text field with validation"""

    def clean(self, value):
        if value and not is_valid_cidr(value):
            raise forms.ValidationError(
                "Value must be a valid CIDR address")
        else:
            return super(CIDRField, self).clean(value)


class PointField(forms.CharField):
    widget = widgets.PointInput

    def clean(self, value):
        if not value or validators.is_valid_point_string(value):
            return super(PointField, self).clean(value)
        raise forms.ValidationError(
            "Invalid format. Point field format is '(x,y)'.")


class HStoreField(Field):

    def __init__(self, **params):
        params['widget'] = params.get('widget', Textarea)
        super(HStoreField, self).__init__(**params)

    def to_python(self, value):
        return validators.validate_hstore(value)

    def render(self, name, value, attrs=None):
        # return json representation of a meaningful value
        # doesn't show anything for None, empty strings or empty dictionaries
        if value and not isinstance(value, six.string_types):
            value = json.dumps(value, sort_keys=True, indent=4)
        return super(HStoreField, self).render(name, value, attrs)
